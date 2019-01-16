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
package org.sonatype.security.cache;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletRequest;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.subject.WebSubject;

public class TalendRequestCache {
    private static final String ATTR_KEY = TalendRequestCache.class.getName();

    private final Map<String, Boolean> permCache = new HashMap<>();
    private volatile AuthenticationInfo authenticationInfo;
    private final Map<Object, AuthorizationInfo> authorizationInfo = new HashMap<>();

    public AuthenticationInfo getAuthenticationInfo() {
        return authenticationInfo;
    }

    public void setAuthenticationInfo(final AuthenticationInfo authenticationInfo) {
        this.authenticationInfo = authenticationInfo;
    }

    public Map<Object, AuthorizationInfo> getAuthorizationInfo() {
        return authorizationInfo;
    }

    public Map<String, Boolean> getPermCache() {
        return permCache;
    }

    public static TalendRequestCache get() {
        return get(SecurityUtils.getSubject());
    }

    public static TalendRequestCache get(final Subject subject) {
        if (!WebSubject.class.isInstance(subject) || !subject.isAuthenticated()) {
            return new TalendRequestCache();
        }
        final ServletRequest request = WebSubject.class.cast(subject).getServletRequest();
        if (request == null) {
            return new TalendRequestCache();
        }
        final TalendRequestCache attribute = TalendRequestCache.class.cast(request.getAttribute(ATTR_KEY));
        if (attribute != null) {
            return attribute;
        }
        final TalendRequestCache cache = new TalendRequestCache();
        request.setAttribute(ATTR_KEY, cache);
        return cache;
    }
}
