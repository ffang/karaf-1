/*
 *  Copyright 2005-2018 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.apache.karaf.shell.ssh;

import java.util.LinkedList;

import org.apache.sshd.SshServer;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;

public class SftpConfigurer {

    private boolean enabled = false;
    private FileSystemFactory fileSystemFactory;
    private NamedFactory<Command> subsystemFactory;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public FileSystemFactory getFileSystemFactory() {
        return fileSystemFactory;
    }

    public void setFileSystemFactory(FileSystemFactory fileSystemFactory) {
        this.fileSystemFactory = fileSystemFactory;
    }

    public NamedFactory<Command> getSubsystemFactory() {
        return subsystemFactory;
    }

    public void setSubsystemFactory(NamedFactory<Command> subsystemFactory) {
        this.subsystemFactory = subsystemFactory;
    }

    public void configure(SshServer server) {
        if (enabled) {
            if (subsystemFactory != null) {
                if (server.getSubsystemFactories() == null) {
                    server.setSubsystemFactories(new LinkedList<NamedFactory<Command>>());
                }
                server.getSubsystemFactories().add(subsystemFactory);
            }
            if (fileSystemFactory != null) {
                server.setFileSystemFactory(fileSystemFactory);
            }
        }
    }

}
