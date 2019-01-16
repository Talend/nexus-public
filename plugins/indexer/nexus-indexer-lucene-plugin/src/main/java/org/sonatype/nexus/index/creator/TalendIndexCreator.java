/**
 * Copyright (C) 2006-2019 Talend Inc. - www.talend.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sonatype.nexus.index.creator;

import static java.util.Arrays.asList;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

import javax.inject.Named;

import org.apache.lucene.document.Document;
import org.apache.maven.index.ArtifactContext;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.Field;
import org.apache.maven.index.IndexerField;
import org.apache.maven.index.IndexerFieldVersion;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.creator.AbstractIndexCreator;
import org.apache.maven.index.util.zip.ZipFacade;
import org.apache.maven.index.util.zip.ZipHandle;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

@Named
@Component(role = IndexCreator.class, hint = "talend")
public class TalendIndexCreator extends AbstractIndexCreator {

    public static final IndexerField FLD_URL_ID = new IndexerField(new Field(null, "urn:talend#", "url", "Artifact Url"),
            IndexerFieldVersion.V3, "url", "Artifact url (tokenized)", org.apache.lucene.document.Field.Store.YES,
            org.apache.lucene.document.Field.Index.ANALYZED);

    public static final IndexerField FLD_LICENSE_ID = new IndexerField(
            new Field(null, "urn:talend#", "license", "Artifact License"), IndexerFieldVersion.V3, "license",
            "Artifact License (tokenized)", org.apache.lucene.document.Field.Store.YES,
            org.apache.lucene.document.Field.Index.ANALYZED);

    public static final IndexerField FLD_LICENSE_URL_ID = new IndexerField(
            new Field(null, "urn:talend#", "licenseUrl", "License Url"), IndexerFieldVersion.V3, "licenseUrl",
            "License Url (tokenized)", org.apache.lucene.document.Field.Store.YES,
            org.apache.lucene.document.Field.Index.ANALYZED);

    private final Collection<IndexerField> indexerFields = asList(FLD_URL_ID, FLD_LICENSE_ID, FLD_LICENSE_URL_ID);

    public TalendIndexCreator() {
        super("talend");
    }

    @Override
    public Collection<IndexerField> getIndexerFields() {
        return indexerFields;
    }

    @Override
    public void populateArtifactInfo(final ArtifactContext artifactContext) {
        final Model model = readPom(artifactContext);
        if (model == null) {
            return;
        }
        final ArtifactInfo info = artifactContext.getArtifactInfo();
        if (model.getUrl() != null) {
            info.getAttributes().put(FLD_URL_ID.getKey(), model.getUrl());
        }
        final List<License> licenses = model.getLicenses();
        if (!licenses.isEmpty()) {
            final License license = licenses.get(0);
            if (license.getName() != null) {
                info.getAttributes().put(FLD_LICENSE_ID.getKey(), license.getName());
            }
            if (license.getUrl() != null) {
                info.getAttributes().put(FLD_LICENSE_URL_ID.getKey(), license.getUrl());
            }
        }
    }

    @Override
    public void updateDocument(final ArtifactInfo artifactInfo, final Document document) {
        final String url = artifactInfo.getAttributes().get(FLD_URL_ID.getKey());
        if (url != null) {
            document.add(FLD_URL_ID.toField(url));
        }
        final String license = artifactInfo.getAttributes().get(FLD_LICENSE_ID.getKey());
        if (license != null) {
            document.add(FLD_LICENSE_ID.toField(license));
        }

        final String licenseUrl = artifactInfo.getAttributes().get(FLD_LICENSE_URL_ID.getKey());
        if (licenseUrl != null) {
            document.add(FLD_LICENSE_URL_ID.toField(licenseUrl));
        }
    }

    @Override
    public boolean updateArtifactInfo(final Document document, final ArtifactInfo info) {
        boolean updated = false;
        final String url = document.get(FLD_URL_ID.getKey());
        if (url != null) {
            info.getAttributes().put(FLD_URL_ID.getKey(), url);
            updated = true;
        }
        final String license = document.get(FLD_LICENSE_ID.getKey());
        if (license != null) {
            info.getAttributes().put(FLD_LICENSE_ID.getKey(), license);
            updated = true;
        }
        final String licenseUrl = document.get(FLD_LICENSE_URL_ID.getKey());
        if (licenseUrl != null) {
            info.getAttributes().put(FLD_LICENSE_URL_ID.getKey(), licenseUrl);
            updated = true;
        }
        return updated;
    }

    // default just read minimal set of meta, we need all the pom meta
    private Model readPom(final ArtifactContext artifactContext) {
        if (artifactContext.getPom() != null && artifactContext.getPom().isFile()) {
            try {
                return new MavenXpp3Reader().read(
                        new FileInputStream(artifactContext.getPom()), false);
            } catch (final IOException | XmlPullParserException e) {
                e.printStackTrace();
            }
        }
        else if (artifactContext.getArtifact() != null && artifactContext.getArtifact().isFile()) {
            ZipHandle handle = null;

            try {
                handle = ZipFacade.getZipHandle(artifactContext.getArtifact());

                final String embeddedPomPath = "META-INF/maven/"
                        + artifactContext.getGav().getGroupId() + "/"
                        + artifactContext.getGav().getArtifactId() + "/pom.xml";

                if (handle.hasEntry(embeddedPomPath)) {
                    return new MavenXpp3Reader().read(
                            handle.getEntryContent(embeddedPomPath), false);
                }
            } catch (final IOException | XmlPullParserException e) {
                e.printStackTrace();
            } finally {
                try {
                    ZipFacade.close(handle);
                } catch (final Exception e) {
                    // no-op
                }
            }
        }

        return null;
    }
}
