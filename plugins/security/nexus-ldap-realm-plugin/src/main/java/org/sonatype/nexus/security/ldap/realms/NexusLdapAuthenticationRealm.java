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
package org.sonatype.nexus.security.ldap.realms;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAccount;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.subject.PrincipalCollection;
import org.sonatype.security.cache.TalendRequestCache;
import org.sonatype.security.ldap.LdapConstants;
import org.sonatype.security.ldap.realms.AbstractLdapAuthenticationRealm;
import org.sonatype.security.ldap.realms.LdapManager;

import org.eclipse.sisu.Description;

@Singleton
@Named(LdapConstants.REALM_NAME)
@Description("OSS LDAP Authentication Realm")
public class NexusLdapAuthenticationRealm
    extends AbstractLdapAuthenticationRealm
{
  private final AuthorizationException preComputedException = new AuthorizationException(
        "LDAP naming error while attempting to retrieve authorization - from NexusLdapAuthenticationRealm cache.");
  private final AuthorizationInfo nullInfo = new SimpleAccount();
  private final AuthorizationInfo exceptionInfo = new SimpleAccount();

  @Inject
  public NexusLdapAuthenticationRealm(final LdapManager ldapManager) {
    super(ldapManager);
    preComputedException.setStackTrace(new StackTraceElement[0]);
  }

  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
    return super.doGetAuthenticationInfo(token);
  }

  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    // talend: cache
    final Map<Object, AuthorizationInfo> cache = TalendRequestCache.get().getAuthorizationInfo();
    final AuthorizationInfo authorizationInfo = cache.get(this);
    if (authorizationInfo != null) {
      if (authorizationInfo == exceptionInfo) {
        throw preComputedException;
      }
      if (authorizationInfo == nullInfo) {
        return null;
      }
      return authorizationInfo;
    }
    try {
      final AuthorizationInfo info = super.doGetAuthorizationInfo(principals);
      cache.put(this, info);
      return info;
    } catch (final AuthorizationException ae) {
      cache.put(this, exceptionInfo);
      throw ae;
    }
  }
}
