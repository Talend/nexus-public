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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.enterprise.inject.Typed;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.RolePermissionResolver;
import org.sonatype.security.authorization.NoSuchPrivilegeException;
import org.sonatype.security.authorization.NoSuchRoleException;
import org.sonatype.security.authorization.PermissionFactory;
import org.sonatype.security.events.AuthorizationConfigurationChanged;
import org.sonatype.security.events.OnShutdown;
import org.sonatype.security.events.SecurityConfigurationChanged;
import org.sonatype.security.model.CPrivilege;
import org.sonatype.security.model.CRole;
import org.sonatype.security.realms.privileges.PrivilegeDescriptor;
import org.sonatype.security.realms.tools.ConfigurationManager;
import org.sonatype.security.realms.tools.StaticSecurityResource;
import org.sonatype.sisu.goodies.common.ComponentSupport;
import org.sonatype.sisu.goodies.eventbus.EventBus;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.MapMaker;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * The default implementation of the RolePermissionResolver which reads roles from {@link StaticSecurityResource}s to
 * resolve a role into a collection of permissions. This class allows Realm implementations to no know what/how there
 * roles are used.
 *
 * @author Brian Demers
 */
@Singleton
@Typed(RolePermissionResolver.class)
@Named("default")
public class XmlRolePermissionResolver
    extends ComponentSupport
    implements RolePermissionResolver
{
  private final ConfigurationManager configuration;

  private final List<PrivilegeDescriptor> privilegeDescriptors;

  private final PermissionFactory permissionFactory;

  /**
   * Privilege-id to permission cache.
   */
  private final Map<String,Permission> permissionsCache;

  /**
   * Role-id to role permissions cache.
   */
  private final Map<String, Collection<Permission>> rolePermissionsCache;

  /**
   * role not found cache.
   */
  private final Cache<String,String> roleNotFoundCache;

  private final ScheduledExecutorService configValidatorPool;
  private final ScheduledFuture<?> refreshFuture;

  private final AtomicReference<String> aPermissionToUpdate = new AtomicReference<>();

  private volatile long lastRefresh = System.currentTimeMillis();

  @Inject
  public XmlRolePermissionResolver(@Named("default") ConfigurationManager configuration,
                                   List<PrivilegeDescriptor> privilegeDescriptors,
                                   @Named("caching") PermissionFactory permissionFactory,
                                   EventBus eventBus,
                                   @Named("${security.roleNotFoundCacheSize:-100000}") int roleNotFoundCacheSize)
  {
    this.configuration = configuration;
    this.privilegeDescriptors = privilegeDescriptors;
    this.permissionFactory = permissionFactory;
    this.permissionsCache = new MapMaker().softValues().makeMap();
    this.rolePermissionsCache = new ConcurrentHashMap<>();
    this.roleNotFoundCache = CacheBuilder.newBuilder().maximumSize(roleNotFoundCacheSize).build();
    eventBus.register(this);

    // Talend: evict like that the permissions to ensure it is up to date but without locking all threads
    final AtomicInteger counter = new AtomicInteger();
    configValidatorPool = Executors.newSingleThreadScheduledExecutor(task -> {
      final Thread thread = new Thread(task, "XmlRolePermissionResolver-" + counter.incrementAndGet());
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    });
    final int refreshDelay = 3;
    refreshFuture = configValidatorPool.scheduleAtFixedRate(() -> {
      final String value = aPermissionToUpdate.get();
      if (value != null) {
        reloadConfigToMakeSureNotDirty(value);
      }
    }, refreshDelay, refreshDelay, TimeUnit.SECONDS);
  }

  /**
   * Invalidate/clear caches.
   */
  private void invalidate() {
    permissionsCache.clear();
    rolePermissionsCache.clear();
    roleNotFoundCache.invalidateAll();
    lastRefresh = System.currentTimeMillis();
    log.trace("Cache invalidated");
  }

  public long getLastRefresh() {
    return lastRefresh;
  }

  @Subscribe
  public void on(final OnShutdown event) {
    refreshFuture.cancel(true);
    configValidatorPool.shutdownNow();
    try {
      configValidatorPool.awaitTermination(2, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    invalidate();
  }


  @AllowConcurrentEvents
  @Subscribe
  public void on(final AuthorizationConfigurationChanged event) {
    invalidate();
  }

  @AllowConcurrentEvents
  @Subscribe
  public void on(final SecurityConfigurationChanged event) {
    invalidate();
  }

  public Collection<Permission> resolvePermissionsInRole(final String roleString) {
    try {
      final Set<Permission> permissions = new LinkedHashSet<Permission>();

      // talend: don't check that in the locked block since it is thread save
      //         and don't reload the config each time but ~once per sec (= accept 1s of dirtyness)

      Collection<Permission> cachedPermissions = rolePermissionsCache.get(roleString);
      if (cachedPermissions != null) {
        if (!cachedPermissions.isEmpty()) {
          permissions.addAll(cachedPermissions);
        }
        return permissions;
      }

      configuration.runRead(() -> resolvePermissionsInRole(roleString, permissions));
      aPermissionToUpdate.set(roleString);
      return permissions;
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  protected void resolvePermissionsInRole(final String roleString, final Collection<Permission> permissions) {

    final LinkedList<String> rolesToProcess = new LinkedList<>();
    final Set<String> processedRoleIds = new LinkedHashSet<>();

    // initial role
    rolesToProcess.add(roleString);

    while (!rolesToProcess.isEmpty()) {
      final String roleId = rolesToProcess.removeFirst();
      if (processedRoleIds.add(roleId)) {

        if (roleNotFoundCache.getIfPresent(roleId) != null) {
          log.trace("Role {} found in NFC, role check skipped", roleId);
          continue; // use cached results
        }

        try {
          CRole role = configuration.readRole(roleId);

          // check memory-sensitive cache (after readRole to allow for the dirty check)
          final Collection<Permission> cachedPermissions = rolePermissionsCache.get(roleId);
          if (cachedPermissions != null) {
            permissions.addAll(cachedPermissions);
            continue; // use cached results
          }

          // process the roles this role has recursively
          rolesToProcess.addAll(role.getRoles());

          // add the permissions this role has
          for (String privilegeId : role.getPrivileges()) {
            Permission permission = permission(privilegeId);
            if (permission != null) {
              permissions.add(permission);
            }
          }
        }
        catch (NoSuchRoleException e) {
          handleNoSuchRole(roleId, e);
        }
      }
    }

    if (rolePermissionsCache.size() > 50000) { // unlikely but let's add a guard
      rolePermissionsCache.clear();
    }

    // cache result of (non-trivial) computation
    rolePermissionsCache.put(roleString, permissions);
  }

  private void reloadConfigToMakeSureNotDirty(final String roleString) {
    try {
      // readRole to check that config is not dirty
      configuration.readRole(roleString);
    }
    catch (NoSuchRoleException e) {
      // no-op
    }
  }

  private void handleNoSuchRole(final String roleId, final NoSuchRoleException e) {
    log.trace("Ignoring missing role: {}", roleId, e);
    roleNotFoundCache.put(roleId, "");
  }

  /**
   * Returns the descriptor for the given privilege-type or {@code null}.
   */
  @Nullable
  private PrivilegeDescriptor descriptor(String privilegeType) {
    assert privilegeType != null;

    for (PrivilegeDescriptor descriptor : privilegeDescriptors) {
      if (privilegeType.equals(descriptor.getType())) {
        return descriptor;
      }
    }

    log.warn("Missing privilege-descriptor for type: {}", privilegeType);
    return null;
  }

  /**
   * Returns the permission for the given privilege-id or {@code null}.
   */
  @Nullable
  private Permission permission(final String privilegeId) {
    assert privilegeId != null;

    Permission permission = permissionsCache.get(privilegeId);
    if (permission == null) {
      try {
        CPrivilege privilege = configuration.readPrivilege(privilegeId);
        PrivilegeDescriptor descriptor = descriptor(privilege.getType());
        if (descriptor != null) {
          final String permissionName = descriptor.buildPermission(privilege);
          if (permissionName != null) {
            permission = permissionFactory.create(permissionName);
            permissionsCache.put(privilegeId, permission);
          }
        }
      }
      catch (NoSuchPrivilegeException e) {
        log.trace("Ignoring missing privilege: {}", privilegeId, e);
      }
    }

    return permission;
  }
}
