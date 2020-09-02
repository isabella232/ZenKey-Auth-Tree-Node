/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2017-2018 ForgeRock AS.
 */


package org.forgerock.openam.auth.nodes;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toMap;
import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.oauth.OAuthClientConfiguration.PROVIDER;
import static org.forgerock.oauth.clients.oauth2.OAuth2Client.DATA;
import static org.forgerock.oauth.clients.oauth2.OAuth2Client.LANDING_PAGE;
import static org.forgerock.oauth.clients.oauth2.OAuth2Client.PKCE_CODE_VERIFIER;
import static org.forgerock.oauth.clients.oauth2.OAuth2Client.STATE;
import static org.forgerock.oauth.clients.oidc.OpenIDConnectClient.NONCE;
import static org.forgerock.openam.auth.node.api.Action.goTo;
import static org.forgerock.openam.auth.node.api.Action.send;
import static org.forgerock.openam.auth.node.api.SharedStateConstants.EMAIL_ADDRESS;
import static org.forgerock.openam.auth.nodes.oauth.SocialOAuth2Helper.ATTRIBUTES_SHARED_STATE_KEY;
import static org.forgerock.openam.auth.nodes.oauth.SocialOAuth2Helper.DEFAULT_OAUTH2_SCOPE_DELIMITER;
import static org.forgerock.openam.auth.nodes.oauth.SocialOAuth2Helper.USER_INFO_SHARED_STATE_KEY;
import static org.forgerock.openam.auth.nodes.oauth.SocialOAuth2Helper.USER_NAMES_SHARED_STATE_KEY;

import org.apache.commons.collections.CollectionUtils;
import org.apache.http.client.utils.URIBuilder;
import org.forgerock.json.JsonValue;
import org.forgerock.json.jose.jwt.JwtClaimsSet;
import org.forgerock.oauth.DataStore;
import org.forgerock.oauth.OAuthClient;
import org.forgerock.oauth.OAuthClientConfiguration;
import org.forgerock.oauth.OAuthException;
import org.forgerock.oauth.clients.oauth2.PkceMethod;
import org.forgerock.oauth.clients.oidc.OpenIDConnectClientConfiguration;
import org.forgerock.oauth.clients.oidc.OpenIDConnectUserInfo;
import org.forgerock.openam.annotations.sm.Attribute;
import org.forgerock.openam.auth.node.api.Action;
import org.forgerock.openam.auth.node.api.ExternalRequestContext;
import org.forgerock.openam.auth.node.api.Node;
import org.forgerock.openam.auth.node.api.NodeProcessException;
import org.forgerock.openam.auth.node.api.SharedStateConstants;
import org.forgerock.openam.auth.node.api.TreeContext;
import org.forgerock.openam.auth.nodes.oauth.AbstractSocialAuthLoginNode;
import org.forgerock.openam.auth.nodes.oauth.ProfileNormalizer;
import org.forgerock.openam.auth.nodes.oauth.SharedStateAdaptor;
import org.forgerock.openam.auth.nodes.oauth.SocialOAuth2Helper;
import org.forgerock.openam.sm.annotations.adapters.Password;
import org.forgerock.openam.sm.validation.URLValidator;
import org.forgerock.util.encode.Base64url;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.assistedinject.Assisted;
import com.iplanet.am.util.SystemProperties;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.client.ClientInformation;
import com.nimbusds.oauth2.sdk.client.ClientMetadata;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.sun.identity.authentication.spi.AuthLoginException;
import com.sun.identity.authentication.spi.RedirectCallback;
import com.sun.identity.shared.Constants;
import com.sun.identity.sm.RequiredValueValidator;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

@Node.Metadata(outcomeProvider = AbstractSocialAuthLoginNode.SocialAuthOutcomeProvider.class,
        configClass = ZenKeyAuthNode.Config.class)
public class ZenKeyAuthNode implements Node {

    static final String ZENKEY_AUTHORIZATION_ENDPOINT = "zenkey_authorization_endpoint";
    static final String ZENKEY_TOKEN_ENDPOINT = "zenkey_token_endpoint";
    static final String ZENKEY_USERINFO_ENDPOINT = "zenkey_userinfo_endpoint";
    static final String ZENKEY_JWK_ENDPOINT = "zenkey_jwk_endpoint";
    static final String ZENKEY_ISSUER = "zenkey_issuer";

    private static final String MAIL_KEY_MAPPING = "mail";
    private final Logger logger = LoggerFactory.getLogger(ZenKeyAuthNode.class);
    private final Config config;
    private final ProfileNormalizer profileNormalizer;
    private final SocialOAuth2Helper authModuleHelper;
    private OAuthClient client;
    private SecureRandom random;


    /**
     * Configuration for the node.
     */
    public interface Config extends AbstractSocialAuthLoginNode.Config {

        /**
         * the client id.
         *
         * @return the client id
         */
        @Attribute(order = 100, validators = {RequiredValueValidator.class})
        String clientId();

        /**
         * The client secret.
         *
         * @return the client secret
         */
        @Attribute(order = 200, validators = {RequiredValueValidator.class})
        @Password
        char[] clientSecret();

        /**
         * The Carrier Discovery URL.
         *
         * @return The Carrier Discovery URL.
         */
        @Attribute(order = 300, validators = {RequiredValueValidator.class, URLValidator.class})
        default String carrierDiscoveryUrl() {
            return "https://discoveryui.myzenkey.com/ui/discovery-ui";
        }

        ;

        /**
         * The OIDC Provider endpoint.
         *
         * @return The authorization endpoint.
         */
        @Attribute(order = 400, validators = {RequiredValueValidator.class, URLValidator.class})
        default String oidcProviderConfigUrl() {
            return "https://discoveryissuer.myzenkey.com/.well-known/openid_configuration";
        }

        ;

        /**
         * The scopes to request.
         *
         * @return the scopes.
         */
        @Attribute(order = 500, validators = {RequiredValueValidator.class})
        default String scopeString() {
            return "openid name email phone postal_code";
        }


        /**
         * The URI the AS will redirect to.
         *
         * @return the redirect URI
         */
        @Attribute(order = 600, validators = {RequiredValueValidator.class, URLValidator.class})
        default String redirectURI() {
            return getServerURL();
        }

        /**
         * The provider. (useful if using IDM)
         *
         * @return the provider.
         */
        @Attribute(order = 700, validators = {RequiredValueValidator.class})
        String provider();

        /**
         * The account provider class.
         *
         * @return The account provider class.
         */
        @Attribute(order = 900, validators = {RequiredValueValidator.class})
        default String cfgAccountProviderClass() {
            return "org.forgerock.openam.authentication.modules.common.mapping.DefaultAccountProvider";
        }

        /**
         * The account mapper class.
         *
         * @return the account mapper class.
         */
        @Attribute(order = 1000, validators = {RequiredValueValidator.class})
        default String cfgAccountMapperClass() {
            return "org.forgerock.openam.authentication.modules.common.mapping.JsonAttributeMapper ";
        }

        /**
         * The attribute mapping classes.
         *
         * @return the attribute mapping classes.
         */
        @Attribute(order = 1100, validators = {RequiredValueValidator.class})
        default Set<String> cfgAttributeMappingClasses() {
            return singleton("org.forgerock.openam.authentication.modules.common.mapping.JsonAttributeMapper");
        }

        /**
         * The account mapper configuration.
         *
         * @return the account mapper configuration.
         */
        @Attribute(order = 1200, validators = {RequiredValueValidator.class})
        default Map<String, String> cfgAccountMapperConfiguration() {
            return singletonMap("sub", "uid");
        }

        /**
         * The attribute mapping configuration.
         *
         * @return the attribute mapping configuration
         */
        @Attribute(order = 1300, validators = {RequiredValueValidator.class})
        default Map<String, String> cfgAttributeMappingConfiguration() {
        	Map<String,String> attributeMapConfiguration = new HashMap<String, String>();
        	attributeMapConfiguration.put("sub", "uuid");
        	attributeMapConfiguration.put("name.given_name", "givenName");
        	attributeMapConfiguration.put("name.family_name", "sn");
        	attributeMapConfiguration.put("email.value", "mail");
        	attributeMapConfiguration.put("postal_code.value", "postalCode");
        	attributeMapConfiguration.put("phone.value", "telephoneNumber");
        	return attributeMapConfiguration;
        }

        /**
         * Specifies if the user attributes must be saved in session.
         *
         * @return true to save the user attribute into the session, false otherwise.
         */
        @Attribute(order = 1400)
        default boolean saveUserAttributesToSession() {
            return true;
        }
    }

    @Inject
    public ZenKeyAuthNode(@Assisted Config config, SocialOAuth2Helper authModuleHelper,
                          ProfileNormalizer profileNormalizer) {
        this.config = config;
        this.authModuleHelper = authModuleHelper;
        this.profileNormalizer = profileNormalizer;
        this.random = new SecureRandom();
    }

    protected static String getServerURL() {
        final String protocol = SystemProperties.get(Constants.AM_SERVER_PROTOCOL);
        final String host = SystemProperties.get(Constants.AM_SERVER_HOST);
        final String port = SystemProperties.get(Constants.AM_SERVER_PORT);
        final String descriptor = SystemProperties.get(Constants.AM_SERVICES_DEPLOYMENT_DESCRIPTOR);

        if (protocol != null && host != null && port != null && descriptor != null) {
            return protocol + "://" + host + ":" + port + descriptor;
        } else {
            return "";
        }
    }

    @Override
    public Action process(TreeContext context) throws NodeProcessException {
        ExternalRequestContext request = context.request;
        JsonValue sharedState = context.sharedState;

        if (!CollectionUtils.isEmpty(request.parameters.get("error"))) {
            logger.debug("Error returned in query parameters of redirect");
            throw new NodeProcessException(String.format("%s: %s", request.parameters.get("error").get(0),
                                                         request.parameters.get("error_description").get(0)));
        } else if (!CollectionUtils.isEmpty(request.parameters.get("mccmnc")) && !CollectionUtils.isEmpty(
                request.parameters.get("code"))) {
            return processOAuthTokenState(context);
        } else if (!CollectionUtils.isEmpty(request.parameters.get("mccmnc"))) {
            return handleAuthorizationRequest(context.request, sharedState);
        }
        return doCarrierDiscovery(sharedState);
    }

    private Action processOAuthTokenState(TreeContext context) throws NodeProcessException {
        Optional<String> user;
        Map<String, Set<String>> attributes;
        Map<String, Set<String>> userNames;
        try {
            org.forgerock.oauth.UserInfo userInfo = getUserInfo(context);

            attributes = profileNormalizer.getNormalisedAttributes(userInfo, getJwtClaims(userInfo), config);
            userNames = profileNormalizer.getNormalisedAccountAttributes(userInfo, getJwtClaims(userInfo), config);

            addLogIfTooManyUsernames(userNames, userInfo);

            user = authModuleHelper.userExistsInTheDataStore(context.sharedState.get("realm").asString(),
                                                             profileNormalizer.getAccountProvider(config), userNames);
        } catch (AuthLoginException e) {
            throw new NodeProcessException(e);
        }

        return getAction(context, user, attributes, userNames);
    }

    private Map<String, ArrayList<String>> convertToMapOfList(Map<String, Set<String>> mapToConvert) {
        return mapToConvert.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> new ArrayList<>(e.getValue())));
    }

    private Action getAction(TreeContext context, Optional<String> user, Map<String,
            Set<String>> attributes, Map<String, Set<String>> userNames) {
        Action.ActionBuilder action;
        if (user.isPresent()) {
            logger.debug("The user {} already have an account. Go to {} outcome",
                         user.get(), AbstractSocialAuthLoginNode.SocialAuthOutcome.ACCOUNT_EXISTS.name());

            action = goTo(AbstractSocialAuthLoginNode.SocialAuthOutcome.ACCOUNT_EXISTS.name())
                    .replaceSharedState(context.sharedState.add(SharedStateConstants.USERNAME, user.get()));
        } else {
            logger.debug("The user doesn't have an account");

            JsonValue sharedState = context.sharedState.put(USER_INFO_SHARED_STATE_KEY,
                                                            json(object(
                                                                    field(ATTRIBUTES_SHARED_STATE_KEY,
                                                                          convertToMapOfList(attributes)),
                                                                    field(USER_NAMES_SHARED_STATE_KEY,
                                                                          convertToMapOfList(userNames))
                                                            )));

            if (attributes.get(MAIL_KEY_MAPPING) != null) {
                sharedState = sharedState.add(EMAIL_ADDRESS, attributes.get(MAIL_KEY_MAPPING).stream().findAny().get());
            } else {
                logger.debug("Unable to ascertain email address because the information is not available. "
                                     +
                                     "It's possible you need to add a scope or that the configured provider does not " +
                                     "have this "
                                     + "information");
            }

            logger.debug("Go to " + AbstractSocialAuthLoginNode.SocialAuthOutcome.NO_ACCOUNT.name() + " outcome");
            action = goTo(AbstractSocialAuthLoginNode.SocialAuthOutcome.NO_ACCOUNT.name()).replaceSharedState(
                    sharedState);
        }

        if (config.saveUserAttributesToSession()) {
            logger.debug("user attributes are going to be saved in the session");
            attributes.forEach((key, value) -> action.putSessionProperty(key, value.stream().findFirst().get()));
        }
        return action.build();
    }

    private org.forgerock.oauth.UserInfo getUserInfo(TreeContext context) throws NodeProcessException {
        this.client = authModuleHelper.newOAuthClient(getOAuthClientConfiguration(config, context));
        DataStore dataStore = SharedStateAdaptor.toDatastore(context.sharedState);
        try {
            if (!context.request.parameters.containsKey("state")) {
                throw new NodeProcessException("Not having the state could mean that this request did not come from "
                                                       + "the IDP");
            }
            HashMap<String, List<String>> parameters = new HashMap<>();
            parameters.put("state", singletonList(context.request.parameters.get("state").get(0)));
            parameters.put("code", singletonList(context.request.parameters.get("code").get(0)));

            logger.debug("fetching the access token ...");
            return client.handlePostAuth(dataStore, parameters)
                         .thenAsync(value -> {
                             logger.debug("Fetch user info from userInfo endpoint");
                             return client.getUserInfo(dataStore);
                         }).getOrThrowUninterruptibly();
        } catch (OAuthException e) {
            throw new NodeProcessException("Unable to get UserInfo details from provider", e);
        }
    }

    private void addLogIfTooManyUsernames(Map<String, Set<String>> userNames, org.forgerock.oauth.UserInfo userInfo) {
        if (userNames.values().size() > 1) {
            if (logger.isWarnEnabled()) {
                String usernamesAsString = config.cfgAccountMapperConfiguration().entrySet()
                                                 .stream()
                                                 .map(entry -> entry.getKey() + " - " + entry.getValue())
                                                 .collect(Collectors.joining(", "));
                logger.warn("Multiple usernames have been found for the user information {} with your configuration "
                                    + "mapping {}", userInfo.toString(), usernamesAsString);
            }
        }
    }

    /**
     * Overriding this method to return JWT claims if the user info is of type OpenIDConnectUserInfo.
     *
     * @param userInfo The user information.
     * @return The jwt claims.
     */
    private JwtClaimsSet getJwtClaims(org.forgerock.oauth.UserInfo userInfo) {
        return ((OpenIDConnectUserInfo) userInfo).getJwtClaimsSet();
    }

    /**
     * Make an HTTP request to the ZenKey discovery issuer endpoint to access the carrier’s OIDC configuration
     */
    private OIDCProviderMetadata discoverIssuer(String mccmnc) throws NodeProcessException {
        URL providerConfigurationURL;
        try {
            providerConfigurationURL = new URIBuilder(config.oidcProviderConfigUrl())
                    .addParameter("client_id", config.clientId())
                    .addParameter("mccmnc", mccmnc)
                    .build().toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new NodeProcessException("Malformed OIDC provider config URI", e);
        }

        // Read all data from URL
        InputStream stream;
        try {
            stream = providerConfigurationURL.openStream();
        } catch (IOException e) {
            throw new NodeProcessException("Unable to connect to provider discovery URI", e);
        }
        String providerInfo;
        try (java.util.Scanner s = new java.util.Scanner(stream)) {
            providerInfo = s.useDelimiter("\\A").hasNext() ? s.next() : "";
        }

        // parse the issuer metadata JSON
        OIDCProviderMetadata providerMetadata;
        try {
            providerMetadata = OIDCProviderMetadata.parse(providerInfo);
        } catch (ParseException e) {
            throw new NodeProcessException("Unable to parse issuer discovery response: " + e.getMessage());
        }
        return providerMetadata;
    }

    private Action doCarrierDiscovery(JsonValue sharedState) throws NodeProcessException {
        // Carrier Discovery:
        // To learn the mccmnc, we send the user to the ZenKey discovery endpoint.
        // This endpoint will redirect the user back to our app, giving us the mccmnc that identifies the user's
        // carrier.

        // save a random state value to prevent request forgeries
        sharedState.put("state", new State().getValue());


        String carrierDiscoveryUrl;
        try {
            carrierDiscoveryUrl = new URIBuilder(config.carrierDiscoveryUrl())
                    .addParameter("client_id", config.clientId())
                    .addParameter("redirect_uri", config.redirectURI())
                    .addParameter("state", sharedState.get("state").asString())
                    .build().toString();
        } catch (URISyntaxException e) {
            throw new NodeProcessException(e);
        }
        RedirectCallback carrierDiscovery = new RedirectCallback(carrierDiscoveryUrl, null, "GET");
        carrierDiscovery.setTrackingCookie(true);
        return send(carrierDiscovery).replaceSharedState(sharedState).build();
    }

    /**
     * Request an auth code
     * The carrier discovery endpoint has redirected back to our app with the mccmnc.
     * Now we can start the authorize flow by requesting an auth code.
     * Send the user to the ZenKey authorization endpoint. After authorization, this endpoint will redirect back to
     * our app with an auth code.
     */
    private Action handleAuthorizationRequest(ExternalRequestContext request, JsonValue sharedState)
            throws NodeProcessException {

        String state = request.parameters.get("state").get(0);
        String mccmnc = request.parameters.get("mccmnc").get(0);
        OIDCProviderMetadata providerMetadata = discoverIssuer(mccmnc);
        sharedState.put(ZENKEY_AUTHORIZATION_ENDPOINT, providerMetadata.getAuthorizationEndpointURI());
        sharedState.put(ZENKEY_TOKEN_ENDPOINT, providerMetadata.getTokenEndpointURI());
        sharedState.put(ZENKEY_USERINFO_ENDPOINT, providerMetadata.getUserInfoEndpointURI());
        sharedState.put(ZENKEY_JWK_ENDPOINT, providerMetadata.getJWKSetURI().toString());
        sharedState.put(ZENKEY_ISSUER, providerMetadata.getIssuer().toString());

        ClientInformation clientInformation = getClientInformation();

        // prevent request forgeries by checking that the incoming state matches
        if (state == null || !state.equals(sharedState.get("state").asString())) {
            throw new NodeProcessException("State mismatch after carrier discovery");
        }

        // persist a state value in the session for the auth redirect
        State authRequestState = new State();
        sharedState.put("state", authRequestState.getValue());

        final String nonce = new BigInteger(160, random).toString(Character.MAX_RADIX);
        byte[] pkceVerifier = new byte[32];
        random.nextBytes(pkceVerifier);

        final JsonValue authRequestDetails = json(object(
                field(PROVIDER, "ZenKey"),
                field(STATE, authRequestState.getValue()),
                field(NONCE, nonce),
                field(DATA, null),
                field(LANDING_PAGE, null),
                field(PKCE_CODE_VERIFIER, Base64url.encode(pkceVerifier))));

        DataStore dataStore = SharedStateAdaptor.toDatastore(json(sharedState));
        try {
            dataStore.storeData(authRequestDetails);
        } catch (OAuthException e) {
            throw new NodeProcessException(e);
        }
        sharedState = SharedStateAdaptor.fromDatastore(dataStore);
        URI redirectURI;
        try {
            redirectURI = new URI(config.redirectURI());
        } catch (URISyntaxException e) {
            throw new NodeProcessException("Malformed redirect URI");
        }

        //build effective scopes
        Scope effectiveScopes = providerMetadata.getScopes();
        effectiveScopes.retainAll(Scope.parse(config.scopeString()));

        // build the auth request
        AuthenticationRequest.Builder authenticationRequestBuilder = new AuthenticationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                effectiveScopes, clientInformation.getID(), redirectURI)
                .endpointURI(providerMetadata.getAuthorizationEndpointURI())
                .state(authRequestState).nonce(new Nonce(nonce));

        if (request.parameters.containsKey("login_hint_token")) {
            authenticationRequestBuilder = authenticationRequestBuilder.customParameter("login_hint_token",
                                                                                        request.parameters
                                                                                                .get("login_hint_token")
                                                                                                .get(0));
        }
        AuthenticationRequest authenticationRequest = authenticationRequestBuilder.build();


        // send user to the ZenKey authorization endpoint to request an authorization code
        RedirectCallback authenticationRequestCallback = new RedirectCallback(authenticationRequest.toURI().toString(),
                                                                              null, "GET");
        authenticationRequestCallback.setTrackingCookie(true);
        return send(authenticationRequestCallback).replaceSharedState(sharedState).build();
    }

    private OAuthClientConfiguration getOAuthClientConfiguration(Config config, TreeContext context) {
        OpenIDConnectClientConfiguration.Builder<?, OpenIDConnectClientConfiguration> builder =
                OpenIDConnectClientConfiguration.openIdConnectClientConfiguration();
        return builder.withClientId(config.clientId())
                      .withClientSecret(new String(config.clientSecret()))
                      .withAuthorizationEndpoint(context.sharedState.get(ZENKEY_AUTHORIZATION_ENDPOINT).asString())
                      .withTokenEndpoint(context.sharedState.get(ZENKEY_TOKEN_ENDPOINT).asString())
                      .withScope(Collections.singletonList(config.scopeString()))
                      .withScopeDelimiter(DEFAULT_OAUTH2_SCOPE_DELIMITER)
                      .withBasicAuth(true)
                      .withUserInfoEndpoint(context.sharedState.get(ZENKEY_USERINFO_ENDPOINT).asString())
                      .withRedirectUri(URI.create(config.redirectURI()))
                      .withProvider(config.provider())
                      .withIssuer(context.sharedState.get(ZENKEY_ISSUER).asString())
                      .withAuthenticationIdKey("sub")
                      .withPkceMethod(PkceMethod.NONE)
                      .withJwk(context.sharedState.get(ZENKEY_JWK_ENDPOINT).asString())
                      .build();
    }


    /**
     * build Nimbus ClientInformation that contains the client ID and secret
     */
    private ClientInformation getClientInformation() {
        ClientMetadata clientMetadata = new ClientMetadata();
        return new ClientInformation(new ClientID(config.clientId()), new Date(), clientMetadata,
                                     new Secret(new String(config.clientSecret())));
    }

}
