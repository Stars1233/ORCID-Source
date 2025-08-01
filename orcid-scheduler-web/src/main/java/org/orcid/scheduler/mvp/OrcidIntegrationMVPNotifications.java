package org.orcid.scheduler.mvp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.orcid.core.manager.ProfileEntityCacheManager;
import org.orcid.core.manager.v3.NotificationManager;
import org.orcid.core.manager.v3.RecordNameManager;
import org.orcid.jaxb.model.v3.release.record.Name;
import org.orcid.persistence.dao.ClientDetailsDao;
import org.orcid.persistence.dao.OrcidOauth2TokenDetailDao;
import org.orcid.persistence.dao.ProfileEmailDomainDao;
import org.orcid.persistence.jpa.entities.ClientDetailsEntity;
import org.orcid.persistence.jpa.entities.NotificationEntity;
import org.orcid.persistence.jpa.entities.ProfileEmailDomainEntity;
import org.orcid.persistence.jpa.entities.ProfileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

public class OrcidIntegrationMVPNotifications {

    private static final Logger LOG = LoggerFactory.getLogger(OrcidIntegrationMVPNotifications.class);

    @Autowired
    private ClientDetailsDao clientDetailsDaoReadOnly;

    @Autowired
    private ProfileEmailDomainDao profileEmailDomainDaoReadOnly;

    @Resource(name = "notificationManagerV3")
    private NotificationManager notificationManager;

    public static final String ORCID_INTEGRATION_NOTIFICATION_FAMILY = "ORCID_INTEGRATION";

    @Value("${org.orcid.notifications.mvp.daysAgo:10}")
    private Long daysAgo;

    @Resource(name = "orcidOauth2TokenDetailDaoReadOnly")
    private OrcidOauth2TokenDetailDao orcidOauth2TokenDetailDaoReadOnly;

    @Resource(name = "recordNameManagerV3")
    private RecordNameManager recordNameManager;

    /**
     * Creates notifications for clients that have enabled the MVP feature
     * 
     * This method checks for clients that have the MVP feature enabled and
     * sends notifications to users whose email domains match the configured
     * domains for each client. Notifications are sent only if no previous
     * notifications have been sent or no notification sent within a specified
     * number of days by default 10.
     */

    public void createOrcidIntegrationNotifications() {
        // Get clients eligible for mvp
        long startTime = System.currentTimeMillis();
        LOG.info("Start process.");
        List<ClientDetailsEntity> clientsWithMVP = clientDetailsDaoReadOnly.findMVPEnabled();
        if (clientsWithMVP != null && !clientsWithMVP.isEmpty()) {
            for (ClientDetailsEntity clientDetails : clientsWithMVP) {
                if (StringUtils.isNotBlank(clientDetails.getNotificationWebpageUrl()) && StringUtils.isNotBlank(clientDetails.getNotificationDomains())) {
                    long startTimeClient = System.currentTimeMillis();
                    Name memberNameObj = recordNameManager.getRecordName(clientDetails.getGroupProfileId());
                    String memberName = null;
                    if (memberNameObj != null) {
                        memberName = memberNameObj.getCreditName() != null ? memberNameObj.getCreditName().getContent() : null;
                    }

                    if (memberName == null) {
                        memberName = clientDetails.getClientName();
                    }

                    LOG.info("Start process client {}. Notification send for the second time if no notifications sent since {} ago", clientDetails.getClientId(),
                            daysAgo);
                    try {
                        JSONArray jsonDomainArr = new JSONArray(clientDetails.getNotificationDomains());
                        Set<ProfileEmailDomainEntity> profileDomainSet = new HashSet<ProfileEmailDomainEntity>();
                        for (int i = 0; i < jsonDomainArr.length(); i++) {
                            String domain = jsonDomainArr.getString(i);
                            List<ProfileEmailDomainEntity> emailDomainList = profileEmailDomainDaoReadOnly.findByEmailDomain(domain);
                            if (emailDomainList != null && emailDomainList.size() > 0) {
                                profileDomainSet.addAll(emailDomainList);
                            }
                        }
                        if (profileDomainSet.size() > 0) {

                            for (ProfileEmailDomainEntity pe : profileDomainSet) {
                                if (!orcidOauth2TokenDetailDaoReadOnly.hasTokenForClient(pe.getOrcid(), clientDetails.getClientId())) {
                                    List<NotificationEntity> orcidIntegrationNotifications = notificationManager.findByOrcidAndClientAndNotificationFamilyNoClientToken(
                                            pe.getOrcid(), clientDetails.getClientId(), ORCID_INTEGRATION_NOTIFICATION_FAMILY);
                                    boolean shouldSendNotification = orcidIntegrationNotifications == null || orcidIntegrationNotifications.isEmpty()
                                            || shouldSendNotification(orcidIntegrationNotifications);
                                    if (shouldSendNotification) {
                                        notificationManager.sendOrcidIntegrationNotificationToUser(pe.getOrcid(), clientDetails, memberName);
                                        LOG.warn("Create notification for client {} and orcid {}", clientDetails.getClientId(), pe.getOrcid());
                                    } else {
                                        LOG.info("Notification already sent for client {} and orcid {}", clientDetails.getClientId(), pe.getOrcid());
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        LOG.error("Cannot set the mvp notifications for client with the id: " + clientDetails.getClientId(), ex);
                    }
                    long endTimeClient = System.currentTimeMillis();
                    long durationMillisClient = endTimeClient - startTimeClient;
                    LOG.info("End process for client {}.", clientDetails.getClientId());
                    LOG.info("Duration process client {}.", durationToString(durationMillisClient));

                } else {
                    LOG.warn("Check the mvp notifications fields for client with the id: " + clientDetails.getClientId()
                            + " at least one of required notification field is empty, therefore the notification cannot be sent.");
                }
            }
        }
        long endTime = System.currentTimeMillis();
        LOG.info("End process.");

        long durationMillis = endTime - startTime;

        LOG.info("Total duration process {}.", durationToString(durationMillis));
    }

    private String durationToString(long durationMillis) {
        long hours = durationMillis / (1000 * 60 * 60);
        long minutes = (durationMillis / (1000 * 60)) % 60;
        long seconds = (durationMillis / 1000) % 60;
        return String.format("%d hours, %d minutes, %d seconds", hours, minutes, seconds);
    }

    private boolean shouldSendNotification(List<NotificationEntity> orcidIntegrationNotifications) {
        if (orcidIntegrationNotifications == null || orcidIntegrationNotifications.isEmpty()) {
            return true;
        }

        if (orcidIntegrationNotifications.size() > 1) {
            return false;
        } else {
            java.util.Date daysAgoDate = new java.util.Date(System.currentTimeMillis() - daysAgo * 24 * 60 * 60 * 1000);
            return orcidIntegrationNotifications.get(0).getDateCreated() != null && orcidIntegrationNotifications.get(0).getDateCreated().before(daysAgoDate);
        }
    }
}
