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
package org.sonatype.security.realms;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.inject.Typed;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.credential.Sha1CredentialsMatcher;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.authz.permission.RolePermissionResolver;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.util.CollectionUtils;
import org.eclipse.sisu.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.security.SecuritySystem;
import org.sonatype.security.authorization.WildcardPermissionFactory;
import org.sonatype.security.cache.TalendRequestCache;
import org.sonatype.security.usermanagement.RoleIdentifier;
import org.sonatype.security.usermanagement.RoleMappingUserManager;
import org.sonatype.security.usermanagement.UserManager;
import org.sonatype.security.usermanagement.UserNotFoundException;

/**
 * An Authorizing Realm backed by an XML file see the security-model-xml module. This model defines users, roles, and
 * privileges. This realm ONLY handles authorization.
 *
 * @author Brian Demers
 */
@Singleton
@Typed(Realm.class)
@Named(XmlAuthorizingRealm.ROLE)
@Description("Xml Authorizing Realm")
public class XmlAuthorizingRealm
    extends AuthorizingRealm
    implements Realm
{
  private static final Logger logger = LoggerFactory.getLogger(XmlAuthorizingRealm.class);

  public static final String ROLE = "XmlAuthorizingRealm";

  private final UserManager userManager;

  private final Map<String, UserManager> userManagerMap;

  private final AtomicInteger cacheSize = new AtomicInteger();
  private final ConcurrentMap<CollectionWrapper, Collection<Permission>> collectionCache = new ConcurrentHashMap<>();

  private final SecuritySystem securitySystem;

  private volatile long lastResolverRefresh;

  @Inject
  public XmlAuthorizingRealm(UserManager userManager, SecuritySystem securitySystem,
                             Map<String, UserManager> userManagerMap)
  {
    this.userManager = userManager;
    this.securitySystem = securitySystem;
    this.userManagerMap = userManagerMap;
    setCredentialsMatcher(new Sha1CredentialsMatcher());
    setName(ROLE);
    setAuthenticationCachingEnabled(false);
    setAuthenticationCache(null);
  }

  @Override
  public boolean supports(AuthenticationToken token) {
    return false;
  }

  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token)
      throws AuthenticationException
  {
    return null;
  }

  @Override
  protected boolean[] isPermitted(final List<Permission> permissions, final AuthorizationInfo info) {
    if (permissions != null && !permissions.isEmpty()) {
      final Collection<Permission> perms = getPermissions(info);
      int size = permissions.size();
      final boolean[] result = new boolean[size];
      int i = 0;
      for (final Permission p : permissions) {
        result[i++] = isPermitted(p, perms); // here is the optim
      }
      return result;
    }
    return new boolean[0];
  }

  @Override // todo: optimize by storing the perm tree (foo:bar:dummy wil lhave a list for foo, then for bar etc)
  protected boolean isPermitted(final Permission permission, final AuthorizationInfo info) {
    Collection<Permission> perms = getPermissions(info);
    return isPermitted(permission, perms);
  }

  private boolean isPermitted(final Permission permission, final Collection<Permission> perms) {
    if (perms != null && !perms.isEmpty()) {
      if (WildcardPermissionFactory.ConstantPermission.class.isInstance(permission)) {
        if (perms.contains(permission)) {
          return true;
        }
      }
      for (Permission perm : perms) {
        if (perm.implies(permission)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  protected Collection<Permission> getPermissions(final AuthorizationInfo info) {
    if (info != null) {
      if (!CollectionUtils.isEmpty(info.getObjectPermissions()) || !CollectionUtils.isEmpty(info.getStringPermissions())) {
        return super.getPermissions(info);
      }
      final RolePermissionResolver resolver = getRolePermissionResolver();
      if (resolver != null && !CollectionUtils.isEmpty(info.getRoles())) {
        if (XmlRolePermissionResolver.class.isInstance(resolver) && XmlRolePermissionResolver.class.cast(resolver).getLastRefresh() != lastResolverRefresh) {
          collectionCache.clear();
          lastResolverRefresh =  XmlRolePermissionResolver.class.cast(resolver).getLastRefresh();
        }

        final CollectionWrapper key = new CollectionWrapper(info.getRoles());
        final Collection<Permission> permissions = collectionCache.get(key);
        if (permissions != null) {
          return permissions;
        }

        final Collection<Permission> perms = new LinkedHashSet<>(key.collection.size());
        for (final String roleName : key.collection) {
          final Collection<Permission> resolved = resolver.resolvePermissionsInRole(roleName);
          if (!CollectionUtils.isEmpty(resolved)) {
            perms.addAll(resolved);
          }
        }
        if (cacheSize.get() > 50000) {
          collectionCache.clear();
        }
        collectionCache.put(key, perms);
        cacheSize.addAndGet(key.collection.size());
        return perms;
      }
    }
    return super.getPermissions(info);
  }

  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    // talend: cache
    final Map<Object, AuthorizationInfo> cache = TalendRequestCache.get().getAuthorizationInfo();
    final AuthorizationInfo authorizationInfo = cache.get(this);
    if (authorizationInfo != null) {
      return authorizationInfo;
    }

    if (principals == null) {
      throw new AuthorizationException("Cannot authorize with no principals.");
    }

    String username = principals.getPrimaryPrincipal().toString();
    Set<String> roles = new HashSet<String>();

    Set<String> realmNames = new HashSet<String>(principals.getRealmNames());

    // if the user belongs to this realm, we are most likely using this realm stand alone, or for testing
    if (!realmNames.contains(this.getName())) {
      // make sure the realm is enabled
      Collection<Realm> configureadRealms = this.securitySystem.getSecurityManager().getRealms();
      boolean foundRealm = false;
      for (Realm realm : configureadRealms) {
        if (realmNames.contains(realm.getName())) {
          foundRealm = true;
          break;
        }
      }
      if (!foundRealm) {
        // user is from a realm that is NOT enabled
        throw new AuthorizationException("User for principals: " + principals.getPrimaryPrincipal()
            + " belongs to a disabled realm(s): " + principals.getRealmNames() + ".");
      }
    }

    // clean up the realm names for processing (replace the Xml*Realm with default)
    cleanUpRealmList(realmNames);

    if (RoleMappingUserManager.class.isInstance(userManager)) {
      for (String realmName : realmNames) {
        try {
          for (RoleIdentifier roleIdentifier : ((RoleMappingUserManager) userManager).getUsersRoles(username,
              realmName)) {
            roles.add(roleIdentifier.getRoleId());
          }
        }
        catch (UserNotFoundException e) {
          if (this.logger.isTraceEnabled()) {
            this.logger.trace("Failed to find role mappings for user: " + username + " realm: "
                + realmName);
          }
        }
      }
    }
    else if (realmNames.contains("default")) {
      try {
        for (RoleIdentifier roleIdentifier : userManager.getUser(username).getRoles()) {
          roles.add(roleIdentifier.getRoleId());
        }
      }
      catch (UserNotFoundException e) {
        throw new AuthorizationException("User for principals: " + principals.getPrimaryPrincipal()
            + " could not be found.", e);
      }

    }
    else
    // user not managed by this Realm
    {
      throw new AuthorizationException("User for principals: " + principals.getPrimaryPrincipal()
          + " not manged by XML realm.");
    }

    SimpleAuthorizationInfo info = new SimpleAuthorizationInfo(roles);
    cache.put(this, info);
    return info;
  }

  private void cleanUpRealmList(Set<String> realmNames) {
    for (UserManager userManager : this.userManagerMap.values()) {
      String authRealmName = userManager.getAuthenticationRealmName();
      if (authRealmName != null && realmNames.contains(authRealmName)) {
        realmNames.remove(authRealmName);
        realmNames.add(userManager.getSource());
      }
    }

    if (realmNames.contains(getName())) {
      realmNames.remove(getName());
      realmNames.add("default");
    }
  }

  private static class CollectionWrapper {
    private final Collection<String> collection;
    private final int hash;

    private CollectionWrapper(final Collection<String> collection) {
      this.collection = collection;
      this.hash = collection.hashCode();
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final CollectionWrapper that = CollectionWrapper.class.cast(o);
      return hash == that.hash &&
              Objects.equals(collection, that.collection);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
