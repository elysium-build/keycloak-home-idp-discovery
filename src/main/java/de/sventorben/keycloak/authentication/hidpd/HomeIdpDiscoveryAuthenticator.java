package de.sventorben.keycloak.authentication.hidpd;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AuthenticationManager;

import java.util.List;

import static org.keycloak.services.validation.Validation.FIELD_USERNAME;

final class HomeIdpDiscoveryAuthenticator extends AbstractUsernameFormAuthenticator {

    private static final Logger LOG = Logger.getLogger(HomeIdpDiscoveryAuthenticator.class);

    private final AbstractHomeIdpDiscoveryAuthenticatorFactory.DiscovererConfig discovererConfig;

    HomeIdpDiscoveryAuthenticator(AbstractHomeIdpDiscoveryAuthenticatorFactory.DiscovererConfig discovererConfig) {
        this.discovererConfig = discovererConfig;
    }

    @Override
    public void authenticate(AuthenticationFlowContext authenticationFlowContext) {
        HomeIdpAuthenticationFlowContext context = new HomeIdpAuthenticationFlowContext(authenticationFlowContext);
        // DEBUGGER
        LOG.infof("context.loginPage().shouldByPass() is '%b' .",
            context.loginPage().shouldByPass());
        if (context.loginPage().shouldByPass()) {
            String usernameHint = usernameHint(authenticationFlowContext, context);
            // DEBUGGER
            LOG.infof("usernameHint is: '%s'", usernameHint);
            if (usernameHint != null && !context.discoverer(discovererConfig).isEpicon(authenticationFlowContext)) {
                String username = setUserInContext(authenticationFlowContext, usernameHint);
                final List<IdentityProviderModel> homeIdps = context.discoverer(discovererConfig).discoverForUser(authenticationFlowContext, username);
                LOG.infof("the size of homeIdp is: %d", homeIdps.size());
                if (!homeIdps.isEmpty()) {
                    context.rememberMe().remember(username);
                    redirectOrChallenge(context, username, homeIdps);
                    return;
                }
            }
            else if(context.discoverer(discovererConfig).isEpicon(authenticationFlowContext)){
                final List<IdentityProviderModel> homeIdps;
                if (usernameHint != null) {
                    String username = setUserInContext(authenticationFlowContext, usernameHint);
                    context.rememberMe().remember(username);
                    homeIdps = context.discoverer(discovererConfig).discoverForUser(authenticationFlowContext, username);
                } else {
                    homeIdps = context.discoverer(discovererConfig).discoverForUser(authenticationFlowContext, "");
                }

                
                LOG.infof("the size of homeIdp is: %d", homeIdps.size());
                if (!homeIdps.isEmpty()) {                    
                    redirectOrChallenge(context, "", homeIdps);
                    return;
                }
            }
        }
        context.authenticationChallenge().forceChallenge();
    }

    private String usernameHint(AuthenticationFlowContext authenticationFlowContext, HomeIdpAuthenticationFlowContext context) {
        String usernameHint = trimToNull(context.loginHint().getFromSession());
        if (usernameHint == null) {
            usernameHint = trimToNull(authenticationFlowContext.getAuthenticationSession().getAuthNote(ATTEMPTED_USERNAME));
        }
        return usernameHint;
    }

    private void redirectOrChallenge(HomeIdpAuthenticationFlowContext context, String username, List<IdentityProviderModel> homeIdps) {
        if ((homeIdps.size() == 1 && !username.isEmpty() ) || context.config().forwardToFirstMatch() ) {
            IdentityProviderModel homeIdp = homeIdps.get(0);
            if (!username.isEmpty()) {
                context.loginHint().setInAuthSession(homeIdp, username);
            }
            context.redirector().redirectTo(homeIdp);
        } else {
            context.authenticationChallenge().forceChallenge(homeIdps);
        }
    }

    @Override
    public void action(AuthenticationFlowContext authenticationFlowContext) {
        MultivaluedMap<String, String> formData = authenticationFlowContext.getHttpRequest().getDecodedFormParameters();
        if (formData.containsKey("cancel")) {
            LOG.debugf("Login canceled");
            authenticationFlowContext.cancelLogin();
            return;
        }

        HomeIdpAuthenticationFlowContext context = new HomeIdpAuthenticationFlowContext(authenticationFlowContext);

        String tryUsername;
        if (context.reauthentication().required() && authenticationFlowContext.getUser() != null) {
            tryUsername = authenticationFlowContext.getUser().getUsername();
        } else {
            tryUsername = formData.getFirst(AuthenticationManager.FORM_USERNAME);
        }

        String username = setUserInContext(authenticationFlowContext, tryUsername);
        if (username == null) {
            LOG.debugf("No username in request");
            return;
        }


        final List<IdentityProviderModel> homeIdps = context.discoverer(discovererConfig).discoverForUser(authenticationFlowContext, username);
        if (homeIdps.isEmpty()) {
            authenticationFlowContext.attempted();
            context.loginHint().setInAuthSession(username);
        } else {
            RememberMe rememberMe = context.rememberMe();
            rememberMe.handleAction(formData);
            rememberMe.remember(username);
            redirectOrChallenge(context, username, homeIdps);
        }
    }

    private String setUserInContext(AuthenticationFlowContext context, String username) {
        username = trimToNull(username);

        if (username == null) {
            LOG.warn("No or empty username found in request");
            context.getEvent().error(Errors.USER_NOT_FOUND);
            Response challengeResponse = challenge(context, getDefaultChallengeMessage(context), FIELD_USERNAME);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER, challengeResponse);
            return null;
        }

        LOG.debugf("Found username '%s' in request", username);
        context.getEvent().detail(Details.USERNAME, username);
        context.getAuthenticationSession().setAuthNote(ATTEMPTED_USERNAME, username);

        return username;
    }

    private static String trimToNull(String username) {
        if (username != null) {
            username = username.trim();
            if ("".equalsIgnoreCase(username))
                username = null;
        }
        return username;
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        return form.createLoginUsername();
    }

    @Override
    protected String getDefaultChallengeMessage(AuthenticationFlowContext context) {
        return context.getRealm().isLoginWithEmailAllowed() ? "invalidUsernameOrEmailMessage" : "invalidUsernameMessage";
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

}
