package org.sonatype.nexus.rest.indexng.talend;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.maven.index.ArtifactContext;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.IndexerField;
import org.apache.maven.index.IndexerFieldVersion;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.creator.AbstractIndexCreator;
import org.apache.maven.index.creator.LegacyDocumentUpdater;
import org.apache.maven.index.util.zip.ZipFacade;
import org.apache.maven.index.util.zip.ZipHandle;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(role = IndexCreator.class, hint = ModuleIndexCreator.ID)
public class ModuleIndexCreator extends AbstractIndexCreator implements
		LegacyDocumentUpdater {

	private static final Logger LOG = LoggerFactory
			.getLogger(ModuleIndexCreator.class);

	public static final String ID = "ModuleIndexCreator";

	// added information for modules
	// public static final IndexerField FLD_DESCRIPTION_ID = new IndexerField(
	// MODULE.DESCRIPTION, IndexerFieldVersion.V3, "description",
	// "Artifact description (tokenized)", Field.Store.NO,
	// Field.Index.ANALYZED);
	public static final IndexerField FLD_URL_ID = new IndexerField(MODULE.URL,
			IndexerFieldVersion.V3, "url", "Artifact url (tokenized)",
			Field.Store.YES, Field.Index.ANALYZED);

	public static final IndexerField FLD_LICENSE_ID = new IndexerField(
			MODULE.LICENSE, IndexerFieldVersion.V3, "license",
			"Artifact License (tokenized)", Field.Store.YES,
			Field.Index.ANALYZED);

	public static final IndexerField FLD_LICENSE_URL_ID = new IndexerField(
			MODULE.LICENSE_URL, IndexerFieldVersion.V3, "licenseUrl",
			"License Url (tokenized)", Field.Store.YES, Field.Index.ANALYZED);

	public ModuleIndexCreator() {
		super(ID);
	}

	public void populateArtifactInfo(ArtifactContext ac) {
		ArtifactInfo ai = ac.getArtifactInfo();

		// ac.getPomModel() this one do not work to get url/license
		// ,maybe because of the index-core version
		Model model = getPomModel(ac);
		if (model != null) {
			ai.getAttributes().put(FLD_URL_ID.getKey(), model.getUrl());
			List<License> licenses = model.getLicenses();
			if (!licenses.isEmpty()) {
				License license = licenses.get(0);
				ai.getAttributes().put(FLD_LICENSE_ID.getKey(),
						license.getName());
				ai.getAttributes().put(FLD_LICENSE_URL_ID.getKey(),
						license.getUrl());
			}
		}

	}

	// copy from latest ArtifactContext
	public Model getPomModel(ArtifactContext ac) {
		if (ac.getPom() != null && ac.getPom().isFile()) {
			try {
				return new MavenXpp3Reader().read(
						new FileInputStream(ac.getPom()), false);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (XmlPullParserException e) {
				e.printStackTrace();
			}
		}
		// Otherwise, check for pom contained in maven generated artifact
		else if (ac.getArtifact() != null && ac.getArtifact().isFile()) {
			ZipHandle handle = null;

			try {
				handle = ZipFacade.getZipHandle(ac.getArtifact());

				final String embeddedPomPath = "META-INF/maven/"
						+ ac.getGav().getGroupId() + "/"
						+ ac.getGav().getArtifactId() + "/pom.xml";

				if (handle.hasEntry(embeddedPomPath)) {
					return new MavenXpp3Reader().read(
							handle.getEntryContent(embeddedPomPath), false);
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (XmlPullParserException e) {
				e.printStackTrace();
			} finally {
				try {
					ZipFacade.close(handle);
				} catch (Exception e) {
				}
			}
		}

		return null;
	}

	public void updateDocument(ArtifactInfo ai, Document doc) {
		if (ai.getAttributes().get(FLD_URL_ID.getKey()) != null) {
			doc.add(FLD_URL_ID.toField(ai.getAttributes().get(
					FLD_URL_ID.getKey())));
		}
		if (ai.getAttributes().get(FLD_LICENSE_ID.getKey()) != null) {
			doc.add(FLD_LICENSE_ID.toField(ai.getAttributes().get(
					FLD_LICENSE_ID.getKey())));
		}

		if (ai.getAttributes().get(FLD_LICENSE_URL_ID.getKey()) != null) {
			doc.add(FLD_LICENSE_URL_ID.toField(ai.getAttributes().get(
					FLD_LICENSE_URL_ID.getKey())));
		}
	}

	public void updateLegacyDocument(ArtifactInfo ai, Document doc) {
		updateDocument(ai, doc);
	}

	public boolean updateArtifactInfo(Document doc, ArtifactInfo ai) {
		boolean res = false;

		String urlInfo = doc.get(FLD_URL_ID.getKey());
		if (urlInfo != null) {
			ai.getAttributes().put(FLD_URL_ID.getKey(), urlInfo);
			res = true;
		}

		String liInfo = doc.get(FLD_LICENSE_ID.getKey());
		if (liInfo != null) {
			ai.getAttributes().put(FLD_LICENSE_ID.getKey(), liInfo);
			res = true;
		}

		String liUrlInfo = doc.get(FLD_LICENSE_URL_ID.getKey());
		if (liUrlInfo != null) {
			ai.getAttributes().put(FLD_LICENSE_URL_ID.getKey(), liUrlInfo);
			res = true;
		}

		return res;
	}

	public String toString() {
		return ID;
	}

	public Collection<IndexerField> getIndexerFields() {
		return Arrays.asList(new IndexerField[] { FLD_URL_ID, FLD_LICENSE_ID,
				FLD_LICENSE_URL_ID });
	}
}
