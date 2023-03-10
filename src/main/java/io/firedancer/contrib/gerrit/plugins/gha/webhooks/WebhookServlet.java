/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2022 Jump Crypto
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.firedancer.contrib.gerrit.plugins.gha.webhooks;

import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.firedancer.contrib.gerrit.plugins.gha.config.Credentials;
import io.firedancer.contrib.gerrit.plugins.gha.webhooks.events.WebhookEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;

@Singleton
public class WebhookServlet extends HttpServlet {
  private static final long MAX_REQUEST_BODY_SIZE = 131072;
  private static final String HUB_SIGNATURE_256_PREFIX = "sha256=";

  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  private final Credentials creds;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final Gson gson;

  @Inject
  WebhookServlet(Credentials creds, DynamicItem<EventDispatcher> eventDispatcher, Gson gson) {
    this.creds = creds;
    this.eventDispatcher = eventDispatcher;
    this.gson = gson;

    if (Strings.isNullOrEmpty(this.creds.getWebhookSecret())) {
      log.atWarning().log("webhook-secret not configured");
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    // If webhook secret is not available, skip.
    if (Strings.isNullOrEmpty(creds.getWebhookSecret())) {
      log.atWarning().log("webhook-secret not configured");
      resp.sendError(
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Misconfigured GitHub webhook server");
      return;
    }

    // If auth header is missing, skip.
    String signature = req.getHeader("x-hub-signature-256");
    if (Strings.isNullOrEmpty(signature)) {
      log.atFiner().log("request missing signature header");
      resp.sendError(SC_UNAUTHORIZED, "Missing GitHub request signature");
      return;
    }

    // Limit max request body size to prevent spam.
    if (req.getContentLengthLong() > MAX_REQUEST_BODY_SIZE) {
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Oversize request body");
      return;
    }

    // Read request body.
    String body;
    try (BufferedReader reader = req.getReader()) {
      body = reader.lines().collect(Collectors.joining());
    }

    // Check request signature.
    boolean signatureValid;
    try {
      signatureValid = validateSignature256(signature, body, req.getCharacterEncoding());
    } catch (NoSuchAlgorithmException e) {
      throw new ServletException(e);
    }

    // If request signature invalid, skip.
    if (!signatureValid) {
      log.atFiner().log("Invalid webhook signature");
      resp.sendError(SC_UNAUTHORIZED);
      return;
    }

    // Find event type.
    String eventName = req.getHeader("x-github-event");
    if (eventName == null) {
      log.atWarning().log("Received webhook without x-github-event header (authenticated)");
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing event name header");
      return;
    }

    // Parse request body.
    JsonObject obj;
    try {
      obj = gson.fromJson(body, JsonObject.class);
    } catch (JsonSyntaxException e) {
      log.atWarning().log("Received invalid JSON (authenticated)");
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON");
      return;
    }

    log.atFiner().log("Received webhook (%s)", eventName);

    // Send event to stream.
    WebhookEvent event = new WebhookEvent(eventName, obj);
    try {
      eventDispatcher.get().postEvent(event);
    } catch (PermissionBackendException e) {
      throw new ServletException(e);
    }
  }

  /**
   * validates callback HMAC-SHA256 signature sent from GitHub
   *
   * @param signatureHeader signature HTTP request header of a GitHub webhook
   * @param body HTTP request body
   * @return true if webhook secret is not configured or signatureHeader is valid against payload
   *     and the secret, false if otherwise.
   */
  private boolean validateSignature256(String signatureHeader, String body, String encoding)
      throws UnsupportedEncodingException, NoSuchAlgorithmException {
    byte[] payload = body.getBytes(encoding == null ? "UTF-8" : encoding);

    if (!StringUtils.startsWith(signatureHeader, HUB_SIGNATURE_256_PREFIX)) {
      log.atFiner().log("Unsupported webhook signature type: %s", signatureHeader);
      return false;
    }
    byte[] signature;
    try {
      signature =
          Hex.decodeHex(signatureHeader.substring(HUB_SIGNATURE_256_PREFIX.length()).toCharArray());
    } catch (DecoderException e) {
      log.atFiner().log("Invalid signature");
      return false;
    }
    return MessageDigest.isEqual(signature, getExpectedSignature256(payload));
  }

  /**
   * Calculates the expected HMAC-SHA256 signature of the payload
   *
   * @param payload payload to calculate a signature for
   * @return signature of the payload
   * @see <a href=
   *     "https://developer.github.com/webhooks/securing/#validating-payloads-from-github">
   *     Validating payloads from GitHub</a>
   */
  private byte[] getExpectedSignature256(byte[] payload) throws NoSuchAlgorithmException {
    SecretKeySpec key = new SecretKeySpec(creds.getWebhookSecret().getBytes(), "HmacSHA256");
    Mac hmac;
    try {
      hmac = Mac.getInstance("HmacSHA256");
      hmac.init(key);
    } catch (InvalidKeyException e) {
      throw new IllegalStateException("WTF: HmacSHA256 key incompatible with HmacSHA256 hasher", e);
    }
    return hmac.doFinal(payload);
  }
}
