/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.security;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedExceptionAction;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;

import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.ipc.Server.Connection;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SECURITY_SASL_CUSTOMIZEDCALLBACKHANDLER_CLASS_KEY;

/**
 * A utility class for dealing with SASL on RPC server
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "MapReduce"})
@InterfaceStability.Evolving
public class SaslRpcServer {
  public static final Logger LOG = LoggerFactory.getLogger(SaslRpcServer.class);
  public static final String SASL_DEFAULT_REALM = "default";
  private static SaslServerFactory saslFactory;

  public enum QualityOfProtection {
    AUTHENTICATION("auth"),
    INTEGRITY("auth-int"),
    PRIVACY("auth-conf");
    
    public final String saslQop;
    
    private QualityOfProtection(String saslQop) {
      this.saslQop = saslQop;
    }
    
    public String getSaslQop() {
      return saslQop;
    }
  }

  @InterfaceAudience.Private
  @InterfaceStability.Unstable
  public AuthMethod authMethod;
  public String mechanism;
  public String protocol;
  public String serverId;
  
  @InterfaceAudience.Private
  @InterfaceStability.Unstable
  public SaslRpcServer(AuthMethod authMethod) throws IOException {
    this.authMethod = authMethod;
    mechanism = authMethod.getMechanismName();    
    switch (authMethod) {
      case SIMPLE: {
        return; // no sasl for simple
      }
      case TOKEN: {
        protocol = "";
        serverId = SaslRpcServer.SASL_DEFAULT_REALM;
        break;
      }
      case KERBEROS: {
        String fullName = UserGroupInformation.getCurrentUser().getUserName();
        if (LOG.isDebugEnabled())
          LOG.debug("Kerberos principal name is " + fullName);
        // don't use KerberosName because we don't want auth_to_local
        String[] parts = fullName.split("[/@]", 3);
        protocol = parts[0];
        // should verify service host is present here rather than in create()
        // but lazy tests are using a UGI that isn't a SPN...
        serverId = (parts.length < 2) ? "" : parts[1];
        break;
      }
      default:
        // we should never be able to get here
        throw new AccessControlException(
            "Server does not support SASL " + authMethod);
    }
  }
  
  @InterfaceAudience.Private
  @InterfaceStability.Unstable
  public SaslServer create(final Connection connection,
                           final Map<String,?> saslProperties,
                           SecretManager<TokenIdentifier> secretManager
      ) throws IOException, InterruptedException {
    UserGroupInformation ugi = null;
    final CallbackHandler callback;
    switch (authMethod) {
      case TOKEN: {
        callback = new SaslDigestCallbackHandler(secretManager, connection);
        break;
      }
      case KERBEROS: {
        ugi = UserGroupInformation.getCurrentUser();
        if (serverId.isEmpty()) {
          throw new AccessControlException(
              "Kerberos principal name does NOT have the expected "
                  + "hostname part: " + ugi.getUserName());
        }
        callback = new SaslGssCallbackHandler();
        break;
      }
      default:
        // we should never be able to get here
        throw new AccessControlException(
            "Server does not support SASL " + authMethod);
    }
    
    final SaslServer saslServer;
    if (ugi != null) {
      saslServer = ugi.doAs(
        new PrivilegedExceptionAction<SaslServer>() {
          @Override
          public SaslServer run() throws SaslException  {
            return saslFactory.createSaslServer(mechanism, protocol, serverId,
                saslProperties, callback);
          }
        });
    } else {
      saslServer = saslFactory.createSaslServer(mechanism, protocol, serverId,
          saslProperties, callback);
    }
    if (saslServer == null) {
      throw new AccessControlException(
          "Unable to find SASL server implementation for " + mechanism);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Created SASL server with mechanism = " + mechanism);
    }
    return saslServer;
  }

  public static void init(Configuration conf) {
    if (saslFactory == null) {
      Security.addProvider(new SaslPlainServer.SecurityProvider());
      // passing null so factory is populated with all possibilities. the
      // properties passed when instantiating a server are what really matter
      saslFactory = new FastSaslServerFactory(null);
    }
  }
  
  static String encodeIdentifier(byte[] identifier) {
    return new String(Base64.encodeBase64(identifier), StandardCharsets.UTF_8);
  }

  static byte[] decodeIdentifier(String identifier) {
    return Base64.decodeBase64(identifier.getBytes(StandardCharsets.UTF_8));
  }

  public static <T extends TokenIdentifier> T getIdentifier(String id,
      SecretManager<T> secretManager) throws InvalidToken {
    byte[] tokenId = decodeIdentifier(id);
    T tokenIdentifier = secretManager.createIdentifier();
    try {
      tokenIdentifier.readFields(new DataInputStream(new ByteArrayInputStream(
          tokenId)));
    } catch (IOException e) {
      throw (InvalidToken) new InvalidToken(
          "Can't de-serialize tokenIdentifier").initCause(e);
    }
    return tokenIdentifier;
  }

  static char[] encodePassword(byte[] password) {
    return new String(Base64.encodeBase64(password),
                      StandardCharsets.UTF_8).toCharArray();
  }

  /**
   * Splitting fully qualified Kerberos name into parts.
   * @param fullName fullName.
   * @return splitKerberosName.
   */
  public static String[] splitKerberosName(String fullName) {
    return fullName.split("[/@]");
  }

  /** Authentication method */
  @InterfaceStability.Evolving
  public enum AuthMethod {
    SIMPLE((byte) 80, ""),
    KERBEROS((byte) 81, "GSSAPI"),
    @Deprecated
    DIGEST((byte) 82, SaslMechanismFactory::getMechanism),
    TOKEN((byte) 82, SaslMechanismFactory::getMechanism),
    PLAIN((byte) 83, "PLAIN");

    /** The code for this method. */
    public final byte code;
    private final Supplier<String> mechanismName;

    private AuthMethod(byte code, String mechanismName) { 
      this(code, () -> mechanismName);
    }

    AuthMethod(byte code, Supplier<String> mechanismName) {
      this.code = code;
      this.mechanismName = mechanismName;
    }

    private static final int FIRST_CODE = values()[0].code;

    /** Return the object represented by the code. */
    private static AuthMethod valueOf(byte code) {
      final int i = (code & 0xff) - FIRST_CODE;
      return i < 0 || i >= values().length ? null : values()[i];
    }

    /**
     * Return the SASL mechanism name.
     * @return mechanismName.
     */
    public String getMechanismName() {
      return mechanismName.get();
    }

    /**
     * Read from in.
     *
     * @param in DataInput.
     * @throws IOException raised on errors performing I/O.
     * @return AuthMethod.
     */
    public static AuthMethod read(DataInput in) throws IOException {
      return valueOf(in.readByte());
    }

    /**
     * Write to out.
     * @param out DataOutput.
     * @throws IOException raised on errors performing I/O.
     */
    public void write(DataOutput out) throws IOException {
      out.write(code);
    }
  };

  /** CallbackHandler for SASL mechanism. */
  @InterfaceStability.Evolving
  public static class SaslDigestCallbackHandler implements CallbackHandler {
    private final CustomizedCallbackHandler customizedCallbackHandler;
    private SecretManager<TokenIdentifier> secretManager;
    private Server.Connection connection; 
    
    public SaslDigestCallbackHandler(
        SecretManager<TokenIdentifier> secretManager,
        Server.Connection connection) {
      this(secretManager, connection, connection.getConf());
    }

    public SaslDigestCallbackHandler(
        SecretManager<TokenIdentifier> secretManager,
        Server.Connection connection,
        Configuration conf) {
      this.secretManager = secretManager;
      this.connection = connection;
      this.customizedCallbackHandler = CustomizedCallbackHandler.get(
          HADOOP_SECURITY_SASL_CUSTOMIZEDCALLBACKHANDLER_CLASS_KEY, conf);
    }

    private char[] getPassword(TokenIdentifier tokenid) throws IOException {
      return encodePassword(secretManager.retriableRetrievePassword(tokenid));
    }

    private char[] getPassword(String name) throws IOException {
      final TokenIdentifier tokenIdentifier = getIdentifier(name, secretManager);
      final UserGroupInformation user = tokenIdentifier.getUser();
      connection.attemptingUser = user;
      LOG.debug("SASL server callback: setting password for client: {}", user);
      return getPassword(tokenIdentifier);
    }

    @Override
    public void handle(Callback[] callbacks) throws UnsupportedCallbackException, IOException {
      NameCallback nc = null;
      PasswordCallback pc = null;
      AuthorizeCallback ac = null;
      List<Callback> unknownCallbacks = null;
      for (Callback callback : callbacks) {
        if (callback instanceof AuthorizeCallback) {
          ac = (AuthorizeCallback) callback;
        } else if (callback instanceof NameCallback) {
          nc = (NameCallback) callback;
        } else if (callback instanceof PasswordCallback) {
          pc = (PasswordCallback) callback;
        } else if (callback instanceof RealmCallback) {
          continue; // realm is ignored
        } else {
          if (unknownCallbacks == null) {
            unknownCallbacks = new ArrayList<>();
          }
          unknownCallbacks.add(callback);
        }
      }
      if (pc != null) {
        pc.setPassword(getPassword(nc.getDefaultName()));
      }
      if (ac != null) {
        String authid = ac.getAuthenticationID();
        String authzid = ac.getAuthorizationID();
        if (authid.equals(authzid)) {
          ac.setAuthorized(true);
        } else {
          ac.setAuthorized(false);
        }
        if (ac.isAuthorized()) {
          if (LOG.isDebugEnabled()) {
            UserGroupInformation logUser =
              getIdentifier(authzid, secretManager).getUser();
            String username = logUser == null ? null : logUser.getUserName();
            LOG.debug("SASL server callback: setting authorizedID: {}", username);
          }
          ac.setAuthorizedID(authzid);
        }
      }
      if (unknownCallbacks != null) {
        final String name = nc != null ? nc.getDefaultName() : null;
        final char[] password = name != null ? getPassword(name) : null;
        customizedCallbackHandler.handleCallbacks(unknownCallbacks, name, password);
      }
    }
  }

  /** CallbackHandler for SASL GSSAPI Kerberos mechanism */
  @InterfaceStability.Evolving
  public static class SaslGssCallbackHandler implements CallbackHandler {

    @Override
    public void handle(Callback[] callbacks) throws
        UnsupportedCallbackException {
      AuthorizeCallback ac = null;
      for (Callback callback : callbacks) {
        if (callback instanceof AuthorizeCallback) {
          ac = (AuthorizeCallback) callback;
        } else {
          throw new UnsupportedCallbackException(callback,
              "Unrecognized SASL GSSAPI Callback");
        }
      }
      if (ac != null) {
        String authid = ac.getAuthenticationID();
        String authzid = ac.getAuthorizationID();
        if (authid.equals(authzid)) {
          ac.setAuthorized(true);
        } else {
          ac.setAuthorized(false);
        }
        if (ac.isAuthorized()) {
          if (LOG.isDebugEnabled())
            LOG.debug("SASL server GSSAPI callback: setting "
                + "canonicalized client ID: " + authzid);
          ac.setAuthorizedID(authzid);
        }
      }
    }
  }
}
