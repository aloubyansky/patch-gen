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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.List;

import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.Patch.PatchType;
import org.jboss.as.patching.metadata.PatchElement;
import org.jboss.as.patching.util.IdentityInfo;
import org.jboss.as.patching.util.PatchRepository;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class AddingAddonPatchesTestCase extends PatchRepoTestBase {

    @Test
    public void testMain() throws Exception {

        final File repoDir = new File(testDir, "repo");
        final PatchRepository repo = PatchRepository.create(repoDir);

        final IdentityInfo product101 = IdentityInfo.Builder.create("product", "1.0.1").build();

        assertFalse(repo.hasPatches("product", "1.0.1"));
        assertFalse(repo.hasAddonPatches(product101));
        assertFalse(repo.hasUpdate("product", "1.0.0"));

        final File oneoff1 = createPatch("product", "1.0.1", "oneoff1", ElementSpec.create("base", "base-patch1"));
        repo.addPatch(oneoff1);

        assertTrue(repo.hasPatches("product", "1.0.1"));
        assertFalse(repo.hasAddonPatches(product101));
        assertFalse(repo.hasUpdate("product", "1.0.0"));

        final File addon1Patch1 = createPatch("product", "1.0.1", "whatever", ElementSpec.create("addon1", true, "addon1-patch1"));
        repo.addPatch(addon1Patch1);

        assertTrue(repo.hasPatches("product", "1.0.1"));
        assertTrue(repo.hasAddonPatches(product101));
        assertFalse(repo.hasUpdate("product", "1.0.0"));

        List<Patch> patchesInfo = repo.getPatchesInfo(product101);
        assertEquals(1, patchesInfo.size());

        assertTrue(repo.hasAddonPatches(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon1", "base").build()));
        assertFalse(repo.hasAddonPatches(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon2", "base").build()));

        final File addon1Patch2 = createPatch("product", "1.0.1", "whatever", ElementSpec.create("addon1", true, "addon1-patch2"));
        repo.addPatch(addon1Patch2);
        final File addon2Patch1 = createPatch("product", "1.0.1", "whatever", ElementSpec.create("addon2", true, "addon2-patch1"));
        repo.addPatch(addon2Patch1);
        assertTrue(repo.hasAddonPatches(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon2", "base").build()));

        List<Patch> addonPatchesInfo = repo.getAddonPatchesInfo(product101);
        assertEquals(3, addonPatchesInfo.size());

        addonPatchesInfo = repo.getAddonPatchesInfo(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon1", "base").build());
        assertEquals(2, addonPatchesInfo.size());

        addonPatchesInfo = repo.getAddonPatchesInfo(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon2", "base").build());
        assertEquals(1, addonPatchesInfo.size());

        addonPatchesInfo = repo.getAddonPatchesInfo(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon0", "1.0").build());
        assertEquals(0, addonPatchesInfo.size());

        Patch updateInfo = repo.getUpdateInfo(product101);
        assertNull(updateInfo);

        final File update1 = createUpdate("product", "1.0.1", "1.0.2", "cp1", "base-cp1");
        repo.addPatch(update1);

        updateInfo = repo.getUpdateInfo(product101);
        assertEquals(1, updateInfo.getElements().size());
        assertFalse(updateInfo.getElements().get(0).getProvider().isAddOn());

        // should fail since the update is not available for the add-on yet
        try {
            repo.getUpdateInfo(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon1", "base").build());
            fail("add-on update is not available for 1.0.2");
        } catch(PatchingException e) {
            // expected
        }

        // the update is created for the allowed identity, although the previous add-on version might not be supported in this target allowed identity
        final File addon1Update1 = createPatch("product", "1.0.2", "whatever", ElementSpec.create("addon1", true, "addon1-1.1", true));
        repo.addPatch(addon1Update1);

        updateInfo = repo.getUpdateInfo(product101);
        assertEquals(1, updateInfo.getElements().size());
        assertFalse(updateInfo.getElements().get(0).getProvider().isAddOn());

        // addon1 base is supported in 1.0.1 but not in 1.0.2
        repo.acceptAddonForIdentity("addon1", "base", "product", "1.0.1", true);
        assertFalse(repo.hasAddonUpdates(IdentityInfo.Builder.create("product", "1.0.1").build()));
        assertFalse(repo.hasAddonUpdates(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon1", "base").build()));
        assertFalse(repo.hasAddonUpdates(IdentityInfo.Builder.create("product", "1.0.2").build()));
        // although addon1 base is not supported in 1.0.2
        assertTrue(repo.hasAddonUpdates(IdentityInfo.Builder.create("product", "1.0.2").addAddOn("addon1", "base").build()));
        assertFalse(repo.hasAddonUpdates(IdentityInfo.Builder.create("product", "1.0.2").addAddOn("addon1", "addon1-1.1").build()));

        updateInfo = repo.getUpdateInfo(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon1", "base").build());
        assertEquals(2, updateInfo.getElements().size());
        PatchElement e = updateInfo.getElements().get(0);
        assertEquals("base", e.getProvider().getName());
        assertFalse(e.getProvider().isAddOn());
        assertEquals(PatchType.CUMULATIVE, e.getProvider().getPatchType());
        assertEquals("base-cp1", e.getId());

        e = updateInfo.getElements().get(1);
        assertEquals("addon1", e.getProvider().getName());
        assertTrue(e.getProvider().isAddOn());
        assertEquals(PatchType.CUMULATIVE, e.getProvider().getPatchType());
        assertEquals("addon1-1.1", e.getId());

        patchesInfo = repo.getPatchesInfo(IdentityInfo.Builder.create("product", "1.0.2").build());
        assertTrue(patchesInfo.isEmpty());

//        final File addOnUpdate = new File(testDir, "addonupdate.zip");
//        repo.getUpdate(IdentityInfo.Builder.create("product", "1.0.1").addAddOn("addon1", "base").build(), "1.0.2", true, addOnUpdate);
    }
}
