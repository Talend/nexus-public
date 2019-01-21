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

import java.io.Serializable;

import javax.enterprise.inject.Typed;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.WildcardPermission;

/**
 * A permission factory that creates instances of Shiro's {@link WildcardPermission} by directly invoking it's
 * constructor with passed in string representation of the permission. This is the default factory, as the
 * {@link WildcardPermission} is the default permission implementation used all over Security.
 *
 * @author cstamas
 * @since sonatype-security 2.8
 */
@Named("wildcard")
@Singleton
@Typed(PermissionFactory.class)
public class WildcardPermissionFactory
    implements PermissionFactory
{
  @Override
  public Permission create(final String permission) {
    if (!isWildcardPerm(permission)) { // talend: this is slow!
      return new ConstantPermission(permission);
    }
    return new WildcardPermission(permission) {
      private final int cachedHash = super.hashCode();

      @Override
      public boolean implies(final Permission p) {
        if (ConstantPermission.class.isInstance(p)) {
          return super.implies(new WildcardPermission(ConstantPermission.class.cast(p).permission));
        }
        return super.implies(p);
      }

      @Override
      public int hashCode() {
        return cachedHash;
      }
    };
  }

  private boolean isWildcardPerm(final String permission) {
    return permission.contains("*") || permission.contains(",");
  }

  private static class ConstantPermission implements Permission, Serializable {
    private final String permission;
    private final int cachedHash = super.hashCode();

    private ConstantPermission(final String permission) {
      this.permission = permission == null ? "" : permission;
    }

    @Override
    public boolean implies(final Permission p) {
      if (!ConstantPermission.class.isInstance(p)) {
        return p.implies(this);
      }
      return ConstantPermission.class.cast(p).permission.equals(permission);
    }

    @Override
    public int hashCode() {
      return cachedHash;
    }

    @Override
    public boolean equals(final Object obj) {
      return ConstantPermission.class.isInstance(obj) && permission.equals(ConstantPermission.class.cast(obj).permission);
    }

    @Override
    public String toString() {
      return permission;
    }
  }
}
