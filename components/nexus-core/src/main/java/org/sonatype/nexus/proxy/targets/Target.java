/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.proxy.targets;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.codehaus.plexus.util.StringUtils;
import org.sonatype.nexus.proxy.registry.ContentClass;

/**
 * This is a repository target.
 *
 * @author cstamas
 */
public class Target
{
  private static final Collection<Predicate<String>> TRUE = singletonList(value -> true);

  private final String id;

  private final String name;

  private final ContentClass contentClass;

  private final Set<String> patternTexts;

  private final Collection<Predicate<String>> matchers;

  public Target(String id, String name, ContentClass contentClass, Collection<String> patternTexts)
      throws PatternSyntaxException
  {
    super();

    this.id = id;

    this.name = name;

    this.contentClass = contentClass;

    this.patternTexts = new HashSet<>(patternTexts);

    if (patternTexts.contains(".*")) {
      this.matchers = TRUE;
    } else {
      this.matchers = new ArrayList<>(patternTexts.size());

      // Talend: we moved from pattern to predicate to ensure we can optimize simple patterns and avoid pattern
      //         compilation
      for (final String patternText : patternTexts) {
        if (patternText.startsWith(".*/org/talend/") && patternText.endsWith(".*")) {// first cause the most common for us
          final String included = patternText.substring(".*".length(), patternText.length() - ".*".length());
          matchers.add(s -> s.startsWith(included));
          break;
        } else if ("(?!.*-sources.*).*".equals(patternText)) {
          matchers.add(s -> !s.contains("-sources"));
          break;
        } else if (".*maven-metadata\\.xml.*".equals(patternText)) {
          matchers.add(s -> s.contains("maven-metadata.xml"));
          break;
        }

        // default nexus impl
        final Pattern pattern = Pattern.compile(patternText);
        matchers.add(s -> pattern.matcher(s).matches());
      }
    }
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public ContentClass getContentClass() {
    return contentClass;
  }

  public Set<String> getPatternTexts() {
    return Collections.unmodifiableSet(patternTexts);
  }

  public boolean isPathContained(ContentClass contentClass, String path) {
    // if is the same or is compatible
    // make sure to check the inverse of the isCompatible too !!
    if (StringUtils.equals(getContentClass().getId(), contentClass.getId())
        || getContentClass().isCompatible(contentClass)
        || contentClass.isCompatible(getContentClass())) {
      // look for pattern matching
      for (final Predicate<String> pattern : matchers) {
        if (pattern.test(path)) {
          return true;
        }
      }
    }

    return false;
  }

}
