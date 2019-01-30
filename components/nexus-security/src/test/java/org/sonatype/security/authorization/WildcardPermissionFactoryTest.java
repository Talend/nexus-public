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
package org.sonatype.security.authorization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.shiro.authz.Permission;
import org.junit.Test;

public class WildcardPermissionFactoryTest {
    @Test
    public void checkConstantMatching() {
        final WildcardPermissionFactory factory = new WildcardPermissionFactory();
        final Permission a = factory.create("constant:a");
        final Permission b = factory.create("constant:a");
        assertEquals(b, a);
        assertEquals(b.hashCode(), a.hashCode());
    }

    @Test
    public void checkWildcardMatching() {
        final WildcardPermissionFactory factory = new WildcardPermissionFactory();
        final Permission a = factory.create("wc:a,b");
        assertTrue(factory.create("wc:*").implies(a));
        assertTrue(a.implies(a));
    }

    @Test
    public void checkWildcardConstantMatching() {
        final WildcardPermissionFactory factory = new WildcardPermissionFactory();
        final Permission a = factory.create("neutral:a,b");
        final Permission b = factory.create("neutral:a");
        assertTrue(a.implies(b));
        assertFalse(b.implies(a));
    }
}
