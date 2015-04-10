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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.logging.PatchLogger;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.PatchXml;

/**
 *
 * @author Alexey Loubyansky
 */
class PatchUtil {

    /**
     * @param patchFile
     * @return
     * @throws PatchingException
     */
    static Patch readMetaData(File patchFile) throws PatchingException {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(patchFile);
            final ZipEntry patchXmlEntry = zipFile.getEntry(PatchXml.PATCH_XML);
            if (patchXmlEntry == null) {
                PatchLogger.ROOT_LOGGER.patchIsMissingFile(PatchXml.PATCH_XML);
            }

            final InputStream patchXmlIs = zipFile.getInputStream(patchXmlEntry);
            try {
                return PatchXml.parse(patchXmlIs).resolvePatch(null, null);
            } catch (XMLStreamException e) {
                throw new PatchingException("Failed to parse " + PatchXml.PATCH_XML + " from " + patchFile.getAbsolutePath(), e);
            } finally {
                IoUtils.safeClose(patchXmlIs);
            }
        } catch (ZipException e) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileIsNotReadable(patchFile.getAbsolutePath()));
        } catch (IOException e) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileIsNotReadable(patchFile.getAbsolutePath()));
        } finally {
            IoUtils.safeClose(zipFile);
        }
    }
}
