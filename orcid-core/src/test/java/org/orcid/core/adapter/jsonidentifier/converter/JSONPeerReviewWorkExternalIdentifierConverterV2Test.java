/**
 * =============================================================================
 *
 * ORCID (R) Open Source
 * http://orcid.org
 *
 * Copyright (c) 2012-2014 ORCID, Inc.
 * Licensed under an MIT-Style License (MIT)
 * http://orcid.org/open-source-license
 *
 * This copyright and license information (including a link to the full license)
 * shall be included in its entirety in all copies or substantial portion of
 * the software.
 *
 * =============================================================================
 */
package org.orcid.core.adapter.jsonidentifier.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.junit.Test;
import org.orcid.jaxb.model.common_v2.Visibility;
import org.orcid.jaxb.model.record_v2.ExternalID;
import org.orcid.jaxb.model.record_v2.PeerReview;
import org.orcid.jaxb.model.record_v2.PeerReviewType;
import org.orcid.jaxb.model.record_v2.Role;
import org.orcid.jaxb.model.record_v2.WorkType;
import org.orcid.persistence.jpa.entities.CompletionDateEntity;
import org.orcid.persistence.jpa.entities.OrgEntity;
import org.orcid.persistence.jpa.entities.PeerReviewEntity;
import org.orcid.persistence.jpa.entities.ProfileEntity;
import org.orcid.persistence.jpa.entities.SourceEntity;

public class JSONPeerReviewWorkExternalIdentifierConverterV2Test {

    private JSONPeerReviewWorkExternalIdentifierConverterV2 converter = new JSONPeerReviewWorkExternalIdentifierConverterV2();

    @Test
    public void testConvertTo() throws JAXBException {
        PeerReview peerReview = getPeerReview();
        assertEquals(
                "{\"relationship\":\"SELF\",\"url\":{\"value\":\"http://orcid.org\"},\"workExternalIdentifierType\":\"DOI\",\"workExternalIdentifierId\":{\"content\":\"peer-review:subject-external-identifier-id\"}}",
                converter.convertTo(peerReview.getSubjectExternalIdentifier(), null));
    }

    @Test
    public void testConvertFrom() {
        PeerReviewEntity peerReview = getPeerReviewEntity();
        ExternalID externalID = converter.convertFrom(peerReview.getSubjectExternalIdentifiersJson(), null);
        assertNotNull(externalID);
        
        assertEquals("source-work-id", externalID.getType());
        assertEquals("peer-review:subject-external-identifier-id", externalID.getValue());
        assertEquals("http://orcid.org", externalID.getUrl().getValue());
    }

    private PeerReview getPeerReview() throws JAXBException {
        JAXBContext context = JAXBContext.newInstance(new Class[] { PeerReview.class });
        Unmarshaller unmarshaller = context.createUnmarshaller();
        String name = "/record_2.0/samples/read_samples/peer-review-full-2.0.xml";
        InputStream inputStream = getClass().getResourceAsStream(name);
        return (PeerReview) unmarshaller.unmarshal(inputStream);
    }

    private PeerReviewEntity getPeerReviewEntity() {
        OrgEntity orgEntity = new OrgEntity();
        orgEntity.setCity("org:city");
        orgEntity.setCountry(org.orcid.jaxb.model.message.Iso3166Country.US);
        orgEntity.setName("org:name");
        orgEntity.setRegion("org:region");
        orgEntity.setUrl("org:url");
        orgEntity.setSource(new SourceEntity("APP-000000001"));

        PeerReviewEntity result = new PeerReviewEntity();
        result.setOrg(orgEntity);
        result.setCompletionDate(new CompletionDateEntity(2015, 1, 1));
        result.setExternalIdentifiersJson(
                "{\"workExternalIdentifier\":[{\"relationship\":\"SELF\",\"url\":{\"value\":\"http://orcid.org\"},\"workExternalIdentifierType\":\"SOURCE_WORK_ID\",\"workExternalIdentifierId\":{\"content\":\"peer-review:external-identifier-id\"}}]}");
        result.setProfile(new ProfileEntity("0000-0001-0002-0003"));
        result.setRole(Role.MEMBER);
        result.setType(PeerReviewType.EVALUATION);
        result.setUrl("peer-review:url");
        result.setSubjectExternalIdentifiersJson(
                "{\"relationship\":\"SELF\",\"url\":{\"value\":\"http://orcid.org\"},\"workExternalIdentifierType\":\"SOURCE_WORK_ID\",\"workExternalIdentifierId\":{\"content\":\"peer-review:subject-external-identifier-id\"}}");
        result.setSubjectContainerName("peer-review:subject-container-name");
        result.setSubjectName("peer-review:subject-name");
        result.setSubjectTranslatedName("peer-review:subject-translated-name");
        result.setSubjectTranslatedNameLanguageCode("en");
        result.setSubjectUrl("peer-review:subject-url");
        result.setSubjectType(WorkType.BOOK_REVIEW);
        result.setVisibility(Visibility.PRIVATE);
        result.setClientSourceId("APP-000000001");
        result.setGroupId("orcid-generated:12345");
        result.setId(12345L);

        return result;
    }

}