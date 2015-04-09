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

/**
 * 
 * @author Alexey Loubyansky
 */
public class ComponentVersion implements Comparable<ComponentVersion> {
    
    public static ComponentVersion fromString(String str) {
    
        if(str == null) {
            throw new IllegalArgumentException("str is null");
        }
        return new ComponentVersion(str);
    }
    
    private final String version;
    
    private ComponentVersion(String version) {
        if(version == null) {
            throw new IllegalArgumentException("version is null");
        }
        this.version = version;
    }
    
    @Override
    public String toString() {
        return version;
    }

    @Override
    public int compareTo(ComponentVersion o) {
        if(o == null) {
            throw new IllegalArgumentException("the argument is null");
        }
        return version.compareTo(o.version);
    }
}
