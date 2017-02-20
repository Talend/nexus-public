package org.sonatype.nexus.rest.indexng.talend;

import org.apache.maven.index.Field;

public interface MODULE {
	public static final String MAVEN_NAMESPACE = "urn:talend#";

	public static final Field DESCRIPTION = new Field(null, "urn:talend#",
			"description", "Artifact Description");

	public static final Field URL = new Field(null, "urn:talend#", "url",
			"Artifact Url");

	public static final Field LICENSE = new Field(null, "urn:talend#",
			"license", "Artifact License");

	public static final Field LICENSE_URL = new Field(null, "urn:talend#",
			"licenseUrl", "License Url");
}
