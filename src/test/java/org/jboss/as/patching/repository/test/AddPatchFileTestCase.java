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

package org.jboss.as.patching.repository.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.util.IdentityInfo;
import org.jboss.as.patching.util.PatchRepository;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class AddPatchFileTestCase extends PatchRepoTestBase {

    @Test
    public void testAddPatches() throws Exception {

        final File repoDir = new File(testDir, "repo");
        final PatchRepository repo = PatchRepository.create(repoDir);

        final IdentityInfo product101 = IdentityInfo.Builder.create("product", "1.0.1").build();

        assertFalse(repo.hasPatches("product", "1.0.0"));
        assertFalse(repo.hasPatches("product", "1.0.1"));
        assertFalse(repo.hasUpdate("product", "1.0.0"));

        final File update1 = createUpdate("product", "1.0.0", "1.0.1", "cp1", "base-cp1");
        repo.addPatch(update1);

        assertFalse(repo.hasPatches("product", "1.0.0"));
        assertFalse(repo.hasPatches("product", "1.0.1"));
        assertTrue(repo.hasUpdate("product", "1.0.0"));

        final File oneoff1 = createPatch("product", "1.0.1", "oneoff1", "base-oneoff1");
        repo.addPatch(oneoff1);

        assertFalse(repo.hasPatches("product", "1.0.0"));
        assertTrue(repo.hasPatches("product", "1.0.1"));
        assertTrue(repo.hasUpdate("product", "1.0.0"));

        List<Patch> patches = repo.getPatchesInfo(product101);
        assertEquals(1, patches.size());
        //System.out.println(repo.getPatchXml("product", "1.0.1", "oneoff1", false));

        final File oneoff2 = createPatch("product", "1.0.1", "oneoff2", "base-oneoff2");
        repo.addPatch(oneoff2);

        assertTrue(repo.hasPatches("product", "1.0.1"));
        assertTrue(repo.hasUpdate("product", "1.0.0"));

        patches = repo.getPatchesInfo(product101);
        assertEquals(2, patches.size());
        //System.out.println(repo.getPatchXml("product", "1.0.1", "oneoff2", false));
        assertNotNull(repo.getUpdateInfo("product", "1.0.0"));


//        repo.getUpdateToLatest("product", "1.0.0", true, new File("/home/olubyans/patches/tests/latest-update.zip"));
//        repo.getPatch("product", "1.0.0", "oneoff1", false, new File("/home/olubyans/patches/tests/oneoff1.zip"));
//        repo.getPatch("product", "1.0.0", "cp1", true, new File("/home/olubyans/patches/tests/cp1.zip"));
    }
}
