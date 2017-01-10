package us.kbase.auth2.lib.identity;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import us.kbase.auth2.lib.exceptions.IdentityRetrievalException;

/** An identity provider for Google accounts.
 * @author gaprice@lbl.gov
 *
 */
public class GoogleIdentityProvider implements IdentityProvider {

	//TODO TEST
	
	/* Get creds: https://console.developers.google.com/apis
	 * Google+ API must be enabled
	 * Docs:
	 * https://developers.google.com/identity/protocols/OAuth2
	 * https://developers.google.com/identity/protocols/OAuth2WebServer
	 * https://developers.google.com/+/web/api/rest/oauth#login-scopes
	 * https://developers.google.com/+/web/api/rest/latest/people/get
	 * https://developers.google.com/+/web/api/rest/latest/people
	 */
	
	private static final String NAME = "Google";
	private static final String SCOPE =
			"https://www.googleapis.com/auth/plus.me profile email";
	private static final String LOGIN_PATH = "/o/oauth2/v2/auth";
	private static final String TOKEN_PATH = "/oauth2/v4/token";
	private static final String IDENTITY_PATH = "/plus/v1/people/me";
	
	//thread safe
	private static final Client CLI = ClientBuilder.newClient();
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private final IdentityProviderConfig cfg;
	
	/** Create an identity provider for Google.
	 * @param idc the configuration for this provider.
	 */
	public GoogleIdentityProvider(final IdentityProviderConfig idc) {
		if (idc == null) {
			throw new NullPointerException("idc");
		}
		if (!NAME.equals(idc.getIdentityProviderName())) {
			throw new IllegalArgumentException("Bad config name: " +
					idc.getIdentityProviderName());
		}
		this.cfg = idc;
	}

	@Override
	public String getProviderName() {
		return NAME;
	}
	
	@Override
	public URI getImageURI() {
		return cfg.getImageURI();
	}

	// state will be url encoded
	@Override
	public URL getLoginURL(final String state, final boolean link) {
		final URI target = UriBuilder.fromUri(toURI(cfg.getLoginURL()))
				.path(LOGIN_PATH)
				.queryParam("scope", SCOPE)
				.queryParam("state", state)
				.queryParam("redirect_uri", link ? cfg.getLinkRedirectURL() :
					cfg.getLoginRedirectURL())
				.queryParam("response_type", "code")
				.queryParam("client_id", cfg.getClientID())
				.queryParam("prompt", "select_account")
				.build();
		return toURL(target);
	}
	
	//Assumes valid URL in URI form
	private URL toURL(final URI baseURI) {
		try {
			return baseURI.toURL();
		} catch (MalformedURLException e) {
			throw new RuntimeException("This should be impossible", e);
		}
	}

	//Assumes valid URI in URL form
	private URI toURI(final URL loginURL) {
		try {
			return loginURL.toURI();
		} catch (URISyntaxException e) {
			throw new RuntimeException("This should be impossible", e);
		}
	}

	@Override
	public Set<RemoteIdentity> getIdentities(final String authcode, final boolean link)
			throws IdentityRetrievalException {
		if (authcode == null || authcode.trim().isEmpty()) {
			throw new IllegalArgumentException("authcode cannot be null or empty");
		}
		final String accessToken = getAccessToken(authcode, link);
		final RemoteIdentity ri = getIdentity(accessToken);
		return new HashSet<>(Arrays.asList(ri));
	}

	private RemoteIdentity getIdentity(final String accessToken)
			throws IdentityRetrievalException {
		final URI target = UriBuilder.fromUri(toURI(cfg.getApiURL())).path(IDENTITY_PATH).build();
		final Map<String, Object> id = googleGetRequest(accessToken, target);
		// could do a whooole lot of type checking here. We'll just assume Google aren't buttholes
		// that change their API willy nilly
		@SuppressWarnings("unchecked")
		final List<Map<String, String>> emails = (List<Map<String, String>>) id.get("emails");
		if (emails == null || emails.isEmpty()) {
			throw new IdentityRetrievalException("No username included in response from " + NAME);
		}
		// we'll also just grab the first email and assume that if it's null or empty something
		// is very wrong @ Google
		final String email = emails.get(0).get("value");
		if (email == null || email.trim().isEmpty()) {
			throw new IdentityRetrievalException("No username included in response from " + NAME);
		}
		return new RemoteIdentity(
				new RemoteIdentityID(NAME, (String) id.get("id")),
				new RemoteIdentityDetails(
						email, // use email for user id
						(String) id.get("displayName"),
						email));
	}

	private Map<String, Object> googleGetRequest(
			final String accessToken,
			final URI target)
			throws IdentityRetrievalException {
		final WebTarget wt = CLI.target(target);
		Response r = null;
		try {
			r = wt.request(MediaType.APPLICATION_JSON_TYPE)
					.header("Authorization", "Bearer " + accessToken)
					.get();
			return processResponse(r, 200);
			//TODO TEST with 500s with HTML
			//TODO IDPROVERR handle {error=?} in object and check response code
		} finally {
			if (r != null) {
				r.close();
			}
		}
	}

	private String getAccessToken(final String authcode, final boolean link)
			throws IdentityRetrievalException {
		final MultivaluedMap<String, String> formParameters =
				new MultivaluedHashMap<>();
		formParameters.add("code", authcode);
		formParameters.add("redirect_uri", link ?
				cfg.getLinkRedirectURL().toString() :
				cfg.getLoginRedirectURL().toString());
		formParameters.add("grant_type", "authorization_code");
		formParameters.add("client_id", cfg.getClientID());
		formParameters.add("client_secret", cfg.getClientSecret());
		
		final URI target = UriBuilder.fromUri(toURI(cfg.getApiURL()))
				.path(TOKEN_PATH).build();
		
		final Map<String, Object> m;
		try {
			m = googlePostRequest(formParameters, target);
		} catch (IdentityRetrievalException e) {
			//hacky. switch to internal exception later
			final String[] msg = e.getMessage().split(":", 2);
			throw new IdentityRetrievalException("Authtoken retrieval failed: " +
					msg[msg.length - 1].trim());
		}
		final String token = (String) m.get("access_token");
		if (token == null || token.trim().isEmpty()) {
			throw new IdentityRetrievalException("No access token was returned by " + NAME);
		}
		return token;
	}

	private Map<String, Object> googlePostRequest(
			final MultivaluedMap<String, String> formParameters,
			final URI target)
			throws IdentityRetrievalException {
		final WebTarget wt = CLI.target(target);
		Response r = null;
		try {
			r = wt.request(MediaType.APPLICATION_JSON_TYPE)
					.post(Entity.form(formParameters));
			return processResponse(r, 200);
		} finally {
			if (r != null) {
				r.close();
			}
		}
	}
	
	private Map<String, Object> processResponse(final Response r, final int expectedCode)
			throws IdentityRetrievalException {
		if (r.getStatus() == expectedCode) {
			try { // could check content-type but same result, so...
				@SuppressWarnings("unchecked")
				final Map<String, Object> m = r.readEntity(Map.class);
				return m;
			} catch (ProcessingException e) { // not json
				// can't get the entity at this point because readEntity closes the stream
				// this should never happen in practice so don't worry about it for now
				throw new IdentityRetrievalException(String.format(
						"Unable to parse response from %s service.", NAME));
			}
		}
		if (r.hasEntity()) {
			final String res = r.readEntity(String.class); // we'll assume here that this is small
			final Map<String, Object> m;
			try {  // could check content-type but same result, so...
				m = MAPPER.readValue(res, new TypeReference<Map<String, Object>>() {});
			} catch (IOException e) { // bad JSON
				throw new IdentityRetrievalException(String.format(
						"Got unexpected HTTP code and unparseable response from %s service: %s.",
						NAME, r.getStatus()) + getTruncatedEntityBody(res));
			}
			if (m.containsKey("error")) {
				throw new IdentityRetrievalException(String.format(
						"%s service returned an error. HTTP code: %s. Error: %s. " +
						"Error description: %s",
						NAME, r.getStatus(), m.get("error"), m.get("error_description")));
				// TODO NOW TEST what do google errors look like?
			} else if (m.containsKey("errors")) { // secondary ID
				// all kinds of type checking could be done here; let's just assume Globus doesn't
				// alter their API willy nilly and not do it
				@SuppressWarnings("unchecked")
				final List<Map<String, String>> errors =
						(List<Map<String, String>>) m.get("errors");
				// just deal with the first error for now, change later if necc
				if (errors == null || errors.isEmpty()) {
					throw new IdentityRetrievalException(String.format(
						"Got unexpected HTTP code with null error in the response body from %s " +
						"service: %s.", NAME, r.getStatus()));
				}
				final Map<String, String> err = errors.get(0);
				// could check the keys exist, but then what? null isn't much worse than reporting
				// a missing key. leave as is for now
				throw new IdentityRetrievalException(String.format(
						"%s service returned an error. HTTP code: %s. Error %s: %s; id: %s",
						NAME, r.getStatus(), err.get("code"), err.get("detail"), err.get("id")));
			} else {
				throw new IdentityRetrievalException(String.format(
						"Got unexpected HTTP code with no error in the response body from %s " +
						"service: %s.", NAME, r.getStatus()));
			}
		}
		throw new IdentityRetrievalException(String.format(
				"Got unexpected HTTP code with no response body from %s service: %s.",
				NAME, r.getStatus()));
	}

	private String getTruncatedEntityBody(final String r) {
		if (r.length() > 1000) {
			return " Truncated response: " + r.substring(0, 1000);
		} else {
			return " Response: " + r;
		}
	}
	
	/** A configuratator for a Google identity provider.
	 * @author gaprice@lbl.gov
	 *
	 */
	public static class GoogleIdentityProviderConfigurator implements
			IdentityProviderConfigurator {

		@Override
		public IdentityProvider configure(final IdentityProviderConfig cfg) {
			return new GoogleIdentityProvider(cfg);
		}

		@Override
		public String getProviderName() {
			return NAME;
		}
	}
}
