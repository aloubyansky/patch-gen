/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.patching.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Alexey Loubyansky
 */
public class IdentityInfo {

    public static class Builder {

        public static Builder create(String identityName, String identityVersion) {
            return new Builder(identityName, identityVersion);
        }

        private final IdentityInfo info;

        private Builder(String identityName, String identityVersion) {
            info = new IdentityInfo(identityName, identityVersion);
        }

        public Builder addAddOn(String name, String version) {
            info.addAddOn(new AddOnInfo(name, version));
            return this;
        }

        public IdentityInfo build() {
            return info;
        }
    }

    public static class AddOnInfo {

        private final String name;
        private final String version;

        private AddOnInfo(String name, String version) {
            if(name == null) {
                throw new IllegalArgumentException("name is null");
            }
            if(version == null) {
                throw new IllegalArgumentException("version is null");
            }
            this.name = name;
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return name + "-" + version;
        }
    }

    private final String identityName;
    private final String identityVersion;
    private List<AddOnInfo> addons = Collections.emptyList();

    private IdentityInfo(String identityName, String identityVersion) {
        if(identityName == null) {
            throw new IllegalArgumentException("identityName is null");
        }
        if(identityVersion == null) {
            throw new IllegalArgumentException("identityVersion is null");
        }
        this.identityName = identityName;
        this.identityVersion = identityVersion;
    }

    public String getName() {
        return identityName;
    }

    public String getVersion() {
        return identityVersion;
    }

    public boolean hasAddOns() {
        return !addons.isEmpty();
    }

    public List<AddOnInfo> getAddOns() {
        return Collections.unmodifiableList(addons);
    }

    private void addAddOn(AddOnInfo addon) {
        if(addon == null) {
            throw new IllegalArgumentException("addon is null");
        }
        switch(addons.size()) {
            case 0:
                addons = Collections.singletonList(addon);
                break;
            case 1:
                addons = new ArrayList<AddOnInfo>(addons);
            default:
                addons.add(addon);
        }
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append(identityName).append('-').append(identityVersion);
        if(!addons.isEmpty()) {
            buf.append(" addons=").append(addons.toString());
        }
        return buf.toString();
    }
}
