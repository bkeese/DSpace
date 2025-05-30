/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import static org.dspace.authenticate.OidcAuthenticationBean.OIDC_AUTH_ATTRIBUTE;

import java.io.IOException;
import java.util.ArrayList;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.core.Utils;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * This class will filter OpenID Connect (OIDC) requests and try and authenticate them.
 * In this case, the actual authentication is performed by OIDC. After authentication succeeds, OIDC will send
 * the authentication data to this filter in order for it to be processed by DSpace.
 *
 * @author Pasquale Cavallo (pasquale.cavallo at 4science dot it)
 */
public class OidcLoginFilter extends StatelessLoginFilter {

    private static final Logger log = LogManager.getLogger(OidcLoginFilter.class);

    private final ConfigurationService configurationService = DSpaceServicesFactory.getInstance()
        .getConfigurationService();

    public OidcLoginFilter(String url, String httpMethod, AuthenticationManager authenticationManager,
            RestAuthenticationService restAuthenticationService) {
        super(url, httpMethod, authenticationManager, restAuthenticationService);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest req, HttpServletResponse res)
        throws AuthenticationException {
        req.setAttribute(OIDC_AUTH_ATTRIBUTE, OIDC_AUTH_ATTRIBUTE);
        // NOTE: because this authentication is implicit, we pass in an empty DSpaceAuthentication
        return authenticationManager.authenticate(new DSpaceAuthentication());
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest req, HttpServletResponse res, FilterChain chain,
        Authentication auth) throws IOException, ServletException {
        restAuthenticationService.addAuthenticationDataForUser(req, res, (DSpaceAuthentication) auth, true);
        redirectAfterSuccess(req, res);
    }

    /**
     * After successful login, redirect to the DSpace URL specified by this OIDC
     * request (in the "redirectUrl" request parameter). If that 'redirectUrl' is
     * not valid or trusted for this DSpace site, then return a 400 error.
     * @param  request
     * @param  response
     * @throws IOException
     */
    private void redirectAfterSuccess(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Get redirect URL from request parameter
        String redirectUrl = request.getParameter("redirectUrl");

        // If redirectUrl unspecified, default to the configured UI
        if (StringUtils.isEmpty(redirectUrl)) {
            redirectUrl = configurationService.getProperty("dspace.ui.url");
        }

        // Validate that the redirectURL matches either the server or UI hostname. It
        // *cannot* be an arbitrary URL.
        String redirectHostName = Utils.getHostName(redirectUrl);
        String serverHostName = Utils.getHostName(configurationService.getProperty("dspace.server.url"));
        ArrayList<String> allowedHostNames = new ArrayList<>();
        allowedHostNames.add(serverHostName);
        String[] allowedUrls = configurationService.getArrayProperty("rest.cors.allowed-origins");
        for (String url : allowedUrls) {
            allowedHostNames.add(Utils.getHostName(url));
        }

        if (StringUtils.equalsAnyIgnoreCase(redirectHostName, allowedHostNames.toArray(new String[0]))) {
            log.debug("OIDC redirecting to " + redirectUrl);
            response.sendRedirect(redirectUrl);
        } else {
            log.error("Invalid OIDC redirectURL=" + redirectUrl + ". URL doesn't match hostname of server or UI!");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid redirectURL! Must match server or ui hostname.");
        }
    }

}
