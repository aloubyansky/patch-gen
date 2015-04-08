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

package org.jboss.as.patching.generator;

import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

/**
 * 
 * @author Alexey Loubyansky
 */
@MessageLogger(projectCode = "PATCHGEN", length = 4)
public interface PatchGenLogger extends BasicLogger {

    PatchGenLogger ROOT = Logger.getMessageLogger(PatchGenLogger.class, "org.jboss.as.patchgen");
    
    @Message(id = Message.NONE, value = "Filesystem path of a pristine unzip of the distribution of the version of the "
            + "software to which the generated patch applies")
    String argAppliesToDist();

    /**
     * Instructions for the {@code -h} and {@code --help} command line arguments.
     *
     * @return the instructions.
     */
    @Message(id = Message.NONE, value = "Display this message and exit")
    String argHelp();

    @Message(id = Message.NONE, value = "Filesystem location to which the generated patch file should be written")
    String argOutputFile();

    @Message(id = Message.NONE, value = "Filesystem path of the patch generation configuration file to use")
    String argPatchConfig();

    @Message(id = Message.NONE, value = "Filesystem path of a pristine unzip of a distribution of software which "
            + "contains the changes that should be incorporated in the patch")
    String argUpdatedDist();

    @Message(id = Message.NONE, value = "Usage: %s [args...]%nwhere args include:")
    String patchGeneratorUsageHeadline(String todo);

    /**
     * Instructions for {@code --version} command line argument.
     *
     * @return the instructions.
     */
    @Message(id = Message.NONE, value = "Print version and exit")
    String argVersion();
}
