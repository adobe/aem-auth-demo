package com.aemauthdemo.core.models;

import com.aemauthdemo.core.services.SiteConfigService;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;

@Model(adaptables = Resource.class)
public class AuthLinksModel {

    private static final Logger LOG = LoggerFactory.getLogger(AuthLinksModel.class);

    @OSGiService(optional = true)
    private SiteConfigService siteConfigService;

    private String publishHost;

    @PostConstruct
    protected void init() {
        LOG.info("AuthLinksModel init: siteConfigService={}", siteConfigService);
        if (siteConfigService != null) {
            publishHost = siteConfigService.getPublishHost();
            LOG.info("AuthLinksModel init: publishHost from service={}", publishHost);
        } else {
            publishHost = "http://localhost:8085";
            LOG.warn("AuthLinksModel init: SiteConfigService not injected, falling back to {}", publishHost);
        }
    }

    public String getPublishHost() {
        return publishHost;
    }

    public String getOauth2PageUrl() {
        LOG.debug("AuthLinksModel getOauth2PageUrl: {}", publishHost);
        return publishHost + "/content/aem-auth-demo/us/en/oauth2-authenticated.html";
    }

    public String getOauth2PageWithRedirectUrl() {
        return publishHost + "/content/aem-auth-demo/us/en/oauth2-authenticated.html?redirect=/content/aem-auth-demo/us/en.html";
    }

    public String getSamlPageUrl() {
        return publishHost + "/content/aem-auth-demo/us/en/saml-authenticated.html";
    }
}
