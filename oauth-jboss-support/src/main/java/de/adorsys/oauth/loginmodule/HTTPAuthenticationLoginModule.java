/**
 * Copyright (C) 2015 Daniel Straub, Sandro Sonntag, Christian Brandenstein, Francis Pouatcha (sso@adorsys.de, dst@adorsys.de, cbr@adorsys.de, fpo@adorsys.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.adorsys.oauth.loginmodule;

import org.apache.http.Consts;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.jboss.security.SimpleGroup;
import org.jboss.security.SimplePrincipal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.Map;

/**
 * HTTPAuthenticationLoginModule
 */
public class HTTPAuthenticationLoginModule implements LoginModule {

	private static final Logger LOG = LoggerFactory.getLogger(HTTPAuthenticationLoginModule.class);

	private static final CloseableHttpClient HTTP_CLIENT;

	private Subject subject;
	private CallbackHandler callbackHandler;
	private Map<String, Object> sharedState;
	private URI restEndpoint;

	private ArrayList<Principal> preparedPrincipals;

	static {
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		HTTP_CLIENT = HttpClients.custom().setConnectionManager(connectionManager).build();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		this.sharedState = (Map<String, Object>) sharedState;

		String restEndpointString = (String) options.get("restEndpoint");
		if (restEndpointString == null) {
			throw new IllegalStateException("Missing required option restEndpoint");
		}
		try {
			restEndpoint = new URI(restEndpointString);
		} catch (URISyntaxException e) {
			throw new IllegalStateException("Missing required option restEndpoint has no url format", e);
		}
	}

	@Override
	public boolean login() throws LoginException {

		NameCallback nameCallback = new NameCallback("name");
		PasswordCallback passwordCallback = new PasswordCallback("password", false);
		try {
			callbackHandler.handle(new Callback[] { nameCallback, passwordCallback });
		} catch (Exception x) {
			throw new LoginException(x.getMessage());
		}

		String username = nameCallback.getName();
		char[] passwordChars = passwordCallback.getPassword();
		String password = passwordChars == null ? null : new String(passwordChars);

		LOG.info("login {}", username);

		try {

			return authenticate(username, password);

		} catch (Exception e) {
			throw new LoginException(e.getMessage());
		}
	}

	private boolean authenticate(String username, String password) throws LoginException {
		HttpHost targetHost = new HttpHost(restEndpoint.getHost(), restEndpoint.getPort(), restEndpoint.getScheme());
		CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(new AuthScope(targetHost.getHostName(), targetHost.getPort()), new UsernamePasswordCredentials(username, password));

		// Create AuthCache instance
		AuthCache authCache = new BasicAuthCache();
		// Generate BASIC scheme object and add it to the local auth cache
		BasicScheme basicAuth = new BasicScheme(Consts.UTF_8);
		authCache.put(targetHost, basicAuth);

		// Add AuthCache to the execution context
		HttpClientContext context = HttpClientContext.create();
		context.setCredentialsProvider(credentialsProvider);
		context.setAuthCache(authCache);

		HttpGet httpGet = new HttpGet(restEndpoint);

		CloseableHttpResponse userInfoResponse = null;
		try {
			userInfoResponse = HTTP_CLIENT.execute(httpGet, context);
			if (userInfoResponse.getStatusLine().getStatusCode() != 200) {
				LOG.error("Authentication failed for user {}, restEndpoint {} HTTP Status {}", username, restEndpoint.toASCIIString(),
						userInfoResponse.getStatusLine());
				throw new LoginException("Authentication failed for user " + username + ", restEndpoint " + restEndpoint.toASCIIString() + " HTTP Status "
						+ userInfoResponse.getStatusLine());
			}
			String userInfoJson = readUserInfo(userInfoResponse);
			JSONObject userInfo = new JSONObject(userInfoJson);
			String principalId = userInfo.getString("principal");
			if (principalId == null) {
				LOG.error("could not read  field 'principal' for user {}. Response: {}", username, userInfoJson);
				throw new LoginException("could not read  field 'principal' for user " + username + ". Response: " + userInfoJson);
			}
			JSONArray roles = userInfo.getJSONArray("roles");

			populateSubject(principalId, roles);

			// we put them to shared stated that other login providers can also
			// authenticate
			sharedState.put("javax.security.auth.login.name", principalId);
			sharedState.put("javax.security.auth.login.password", password);
		} catch (IOException e) {
			throw new IllegalStateException("problem on http backend authentication", e);
		} finally {
			if (userInfoResponse != null) {
				try {
					userInfoResponse.close();
				} catch (IOException e) {
					; // NOOP
				}
			}
		}
		return true;
	}

	private SimplePrincipal populateSubject(String principalId, Iterable<Object> roles) {
		preparedPrincipals = new ArrayList<>();
		SimplePrincipal principal = new SimplePrincipal(principalId);
		preparedPrincipals.add(principal);
		Group callerGroup = new SimpleGroup("CallerPrincipal");
		preparedPrincipals.add(callerGroup);
		callerGroup.addMember(principal);
		Group rolesGroup = new SimpleGroup("Roles");
		preparedPrincipals.add(rolesGroup);
		if (roles != null) {
			for (Object object : roles) {
				if (object instanceof String) {
					rolesGroup.addMember(new SimplePrincipal((String) object));
				}
			}
		}
		return principal;
	}

	private String readUserInfo(CloseableHttpResponse userInfoResponse) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		userInfoResponse.getEntity().writeTo(baos);
		String content = new String(baos.toByteArray(), "UTF-8");

		LOG.debug("read userinfo {}", content);
		return content;
	}

	@Override
	public boolean commit() throws LoginException {
		if (preparedPrincipals != null) {
			subject.getPrincipals().addAll(preparedPrincipals);
			return true;
		}
		return false;
	}

	@Override
	public boolean abort() throws LoginException {
		return logout();
	}

	@Override
	public boolean logout() throws LoginException {
		this.subject = null;
		preparedPrincipals = null;
		return true;
	}

}
