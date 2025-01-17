package org.orcid.api.filters;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.orcid.core.manager.ClientDetailsEntityCacheManager;
import org.orcid.core.manager.TemplateManager;
import org.orcid.core.manager.impl.OrcidUrlManager;
import org.orcid.core.manager.v3.EmailManager;
import org.orcid.core.manager.v3.RecordNameManager;
import org.orcid.core.oauth.service.OrcidTokenStore;
import org.orcid.persistence.dao.PublicApiDailyRateLimitDao;
import org.orcid.persistence.jpa.entities.ClientDetailsEntity;
import org.orcid.persistence.jpa.entities.PublicApiDailyRateLimitEntity;
import org.orcid.utils.email.MailGunManager;
import org.orcid.utils.panoply.PanoplyPapiDailyRateExceededItem;
import org.orcid.utils.panoply.PanoplyRedshiftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.orcid.core.togglz.Features;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    @Autowired
    private PublicApiDailyRateLimitDao papiRateLimitingDao;

    @Autowired
    private ClientDetailsEntityCacheManager clientDetailsEntityCacheManager;

    @Autowired
    private MailGunManager mailGunManager;

    @Autowired
    private OrcidUrlManager orcidUrlManager;

    @Autowired
    private RecordNameManager recordNameManager;

    @Autowired
    private TemplateManager templateManager;

    @Autowired
    private EmailManager emailManager;

    @Resource
    private PanoplyRedshiftClient panoplyClient;

    @Autowired
    private OrcidTokenStore orcidTokenStore;

    @Autowired
    private MessageSource messageSource;

    @Value("${org.orcid.papi.rate.limit.anonymous.requests:10000}")
    private int anonymousRequestLimit;

    @Value("${org.orcid.papi.rate.limit.known.requests:40000}")
    private int knownRequestLimit;

    @Value("${org.orcid.papi.rate.limit.enabled:false}")
    private boolean enableRateLimiting;

    @Value("${org.orcid.persistence.panoply.papiExceededRate.production:false}")
    private boolean enablePanoplyPapiExceededRateInProduction;

    @Value("${org.orcid.papi.rate.limit.ip.whiteSpaceSeparatedWhiteList:192.168.65.1 127.0.0.1}")
    private String papiWhiteSpaceSeparatedWhiteList;

    @Value("${org.orcid.papi.rate.limit.clientId.whiteSpaceSeparatedWhiteList}")
    private String papiClientIdWhiteSpaceSeparatedWhiteList;
    
    @Value("${org.orcid.papi.rate.limit.referrer.whiteSpaceSeparatedWhiteList}")
    private String papiReferrerWhiteSpaceSeparatedWhiteList;

    private List<String> papiIpWhiteList;
    private List<String> papiClientIdWhiteList;
    private List<String> papiReferrerWhiteList;

    private static final String TOO_MANY_REQUESTS_MSG = "Too Many Requests. You have exceeded the daily quota for anonymous usage of this API. \n"
            + "You can increase your daily quota by registering for and using Public API client credentials "
            + "(https://info.orcid.org/documentation/integration-guide/registering-a-public-api-client/)";

    private static final String SUBJECT = "[ORCID-API] WARNING! You have exceeded the daily Public API Usage Limit - ({ORCID-ID})";

    @Value("${org.orcid.papi.rate.limit.fromEmail:apiusage@orcid.org}")
    private String FROM_ADDRESS;

    @Value("${org.orcid.papi.rate.limit.ccAddress:membersupport@orcid.org}")
    private String CC_ADDRESS;

    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();
        papiIpWhiteList = StringUtils.isNotBlank(papiWhiteSpaceSeparatedWhiteList) ? Arrays.asList(papiWhiteSpaceSeparatedWhiteList.split("\\s")) : null;
        papiClientIdWhiteList = StringUtils.isNotBlank(papiClientIdWhiteSpaceSeparatedWhiteList) ? Arrays.asList(papiClientIdWhiteSpaceSeparatedWhiteList.split("\\s")) : null;
        papiReferrerWhiteList = StringUtils.isNotBlank(papiReferrerWhiteSpaceSeparatedWhiteList) ? Arrays.asList(papiReferrerWhiteSpaceSeparatedWhiteList.split("\\s")) : null;
    }

    @Override
    public void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain)
            throws ServletException, IOException {
        LOG.trace("ApiRateLimitFilter starts, rate limit is : " + enableRateLimiting);

        if (enableRateLimiting && !isReferrerWhiteListed(httpServletRequest.getHeader(HttpHeaders.REFERER))) {
            String tokenValue = null;
            if (httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION) != null) {
                tokenValue = httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION).replaceAll("Bearer|bearer", "").trim();
            }
            String ipAddress = getClientIpAddress(httpServletRequest);

            String clientId = null;
            if (tokenValue != null) {
                try {
                    clientId = orcidTokenStore.readClientId(tokenValue);
                } catch (Exception ex) {
                    LOG.error("Exception when trying to get the client id from token value, ignoring and treating as anonymous client", ex);
                }
            }
            boolean isAnonymous = (clientId == null);
            LocalDate today = LocalDate.now();
            try {
                if (isAnonymous) {
                    if (!isWhiteListed(ipAddress)) {
                        LOG.info("ApiRateLimitFilter anonymous request for ip: " + ipAddress);
                        this.rateLimitAnonymousRequest(ipAddress, today, httpServletResponse);
                    }

                } else {
                    if (!isClientIdWhiteListed(clientId)) {
                        LOG.info("ApiRateLimitFilter client request with clientId: " + clientId);
                        this.rateLimitClientRequest(clientId, today);
                    }
                }
            } catch (Exception ex) {
                LOG.error("Papi Limiting Filter unexpected error, ignore and chain request.", ex);
            }
        }

        filterChain.doFilter(httpServletRequest, httpServletResponse);
    }

    private void rateLimitAnonymousRequest(String ipAddress, LocalDate today, HttpServletResponse httpServletResponse) throws IOException {
        PublicApiDailyRateLimitEntity rateLimitEntity = papiRateLimitingDao.findByIpAddressAndRequestDate(ipAddress, today);
        if (rateLimitEntity != null) {
            // update the request count only when limit not exceeded ?
            rateLimitEntity.setRequestCount(rateLimitEntity.getRequestCount() + 1);
            papiRateLimitingDao.updatePublicApiDailyRateLimit(rateLimitEntity, false);
            if (rateLimitEntity.getRequestCount() == knownRequestLimit && enablePanoplyPapiExceededRateInProduction) {
                PanoplyPapiDailyRateExceededItem item = new PanoplyPapiDailyRateExceededItem();
                item.setIpAddress(ipAddress);
                item.setRequestDate(rateLimitEntity.getRequestDate());
                setPapiRateExceededItemInPanoply(item);
            }
            if (Features.ENABLE_PAPI_RATE_LIMITING.isActive()) {
                if (rateLimitEntity.getRequestCount() >= anonymousRequestLimit) {
                    httpServletResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    if (!httpServletResponse.isCommitted()) {
                        try (PrintWriter writer = httpServletResponse.getWriter()) {
                            writer.write(TOO_MANY_REQUESTS_MSG);
                            writer.flush();
                        }
                        return;
                    }
                }
            }
        } else {
            // create
            rateLimitEntity = new PublicApiDailyRateLimitEntity();
            rateLimitEntity.setIpAddress(ipAddress);
            rateLimitEntity.setRequestCount(1L);
            rateLimitEntity.setRequestDate(today);
            papiRateLimitingDao.persist(rateLimitEntity);

        }
        return;
    }

    private void rateLimitClientRequest(String clientId, LocalDate today) {
        PublicApiDailyRateLimitEntity rateLimitEntity = papiRateLimitingDao.findByClientIdAndRequestDate(clientId, today);
        if (rateLimitEntity != null) {
            if (Features.ENABLE_PAPI_RATE_LIMITING.isActive()) {
                // email the client first time the limit is reached
                if (rateLimitEntity.getRequestCount() == knownRequestLimit) {
                    sendEmail(clientId, rateLimitEntity.getRequestDate());
                }
            }
            // update the request count
            rateLimitEntity.setRequestCount(rateLimitEntity.getRequestCount() + 1);
            papiRateLimitingDao.updatePublicApiDailyRateLimit(rateLimitEntity, true);
        } else {
            // create
            rateLimitEntity = new PublicApiDailyRateLimitEntity();
            rateLimitEntity.setClientId(clientId);
            rateLimitEntity.setRequestCount(1L);
            rateLimitEntity.setRequestDate(today);
            papiRateLimitingDao.persist(rateLimitEntity);
        }

    }

    private Map<String, Object> createTemplateParams(String clientId, String clientName, String emailName, String orcidId) {
        Map<String, Object> templateParams = new HashMap<String, Object>();
        templateParams.put("messages", messageSource);
        templateParams.put("messageArgs", new Object[0]);
        templateParams.put("clientId", clientId);
        templateParams.put("clientName", clientName);
        templateParams.put("emailName", emailName);
        templateParams.put("locale", LocaleUtils.toLocale("en"));
        templateParams.put("baseUri", orcidUrlManager.getBaseUrl());
        templateParams.put("baseUriHttp", orcidUrlManager.getBaseUriHttp());
        templateParams.put("subject", SUBJECT.replace("{ORCID-ID}", orcidId));
        return templateParams;
    }

    private void sendEmail(String clientId, LocalDate requestDate) {
        ClientDetailsEntity clientDetailsEntity = clientDetailsEntityCacheManager.retrieve(clientId);
        String memberId = clientDetailsEntity.getGroupProfileId();
        String emailName = recordNameManager.deriveEmailFriendlyName(memberId);
        Map<String, Object> templateParams = this.createTemplateParams(clientId, clientDetailsEntity.getClientName(), emailName, memberId);
        // Generate body from template
        String body = templateManager.processTemplate("papi_rate_limit_email.ftl", templateParams);
        // Generate html from template
        String html = templateManager.processTemplate("papi_rate_limit_email_html.ftl", templateParams);
        String email = emailManager.findPrimaryEmail(memberId).getEmail();
        LOG.info("from address={}", FROM_ADDRESS);
        LOG.info("text email={}", body);
        LOG.info("html email={}", html);
        if (enablePanoplyPapiExceededRateInProduction) {
            PanoplyPapiDailyRateExceededItem item = new PanoplyPapiDailyRateExceededItem();
            item.setClientId(clientId);
            item.setOrcid(memberId);
            item.setEmail(email);
            item.setRequestDate(requestDate);
            setPapiRateExceededItemInPanoply(item);
        }

        String subject = templateParams.containsKey("subject") ? ((String) templateParams.get("subject")) : SUBJECT;
        // Send the email
        boolean mailSent = mailGunManager.sendEmailWithCC(FROM_ADDRESS, email, CC_ADDRESS, subject, body, html);
        if (!mailSent) {
            LOG.error("Failed to send email for papi limits, orcid=" + memberId + " email: " + email);
        }
    }

    private void setPapiRateExceededItemInPanoply(PanoplyPapiDailyRateExceededItem item) {
        // Store the rate exceeded item in panoply Db without blocking
        CompletableFuture.supplyAsync(() -> {
            try {
                panoplyClient.addPanoplyPapiDailyRateExceeded(item);
                return true;
            } catch (Exception e) {
                LOG.error("Cannot store the rateExceededItem to panoply ", e);
                return false;
            }
        }).thenAccept(result -> {
            if (!result) {
                LOG.error("Async call to panoply for : " + item.toString() + " Stored: " + result);
            }
        });
    }

    // gets actual client IP address, using the headers that the proxy server adds
    private String getClientIpAddress(HttpServletRequest request) {
        String ipAddress = request.getHeader("X-FORWARDED-FOR");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getHeader("X-REAL-IP");
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }
        if (ipAddress != null && ipAddress.contains(",")) {
            ipAddress = ipAddress.split(",")[0].trim();
        }
        return ipAddress;
    }

    private boolean isWhiteListed(String ipAddress) {
        return (papiIpWhiteList != null) ? papiIpWhiteList.contains(ipAddress) : false;
    }

    private boolean isClientIdWhiteListed(String clientId) {
        return (papiClientIdWhiteList != null) ? papiClientIdWhiteList.contains(clientId) :false;
    }
    
    private boolean isReferrerWhiteListed(String referrer) {
        if (referrer == null)
            return false;
        else
            return (papiReferrerWhiteList != null) ? papiReferrerWhiteList.contains(referrer) :false;
    }
}
