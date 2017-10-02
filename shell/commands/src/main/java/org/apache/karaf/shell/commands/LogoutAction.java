/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.shell.commands;

import org.apache.felix.gogo.commands.Command;
import org.apache.karaf.jaas.boot.principal.UserPrincipal;
import org.apache.karaf.shell.console.AbstractAction;
import org.apache.karaf.shell.console.BundleContextAware;
import org.apache.karaf.shell.console.CloseShellException;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;
import java.io.IOException;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.util.Dictionary;
import java.util.Set;

@Command(scope = "shell", name = "logout", description = "Disconnects shell from current session.")
public class LogoutAction extends AbstractAction implements BundleContextAware{

    protected BundleContext bundleContext;

    protected Object doExecute() throws Exception {
        log.info("Disconnecting from current session...");
        ConfigurationAdmin admin = getConfigurationAdmin();

        Configuration configuration = admin.getConfiguration("org.apache.karaf.shell");
        Dictionary<String, Object> properties = configuration.getProperties();
        String realm=(String) properties.get("sshRealm");
        if(realm == null || "".equals(realm.trim())){
            log.error("Current instance has no configured value for property org.apache.karaf.shell/sshRealm in ConfigAdmin!");
            return null;
        }

        AccessControlContext context = AccessController.getContext();
        Subject subject = Subject.getSubject(context);

        Set<UserPrincipal> principals = subject.getPrincipals(UserPrincipal.class);
        final String username = principals.iterator().next().getName();

        LoginContext loginContext = new LoginContext(realm, subject, new CallbackHandler() {
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                for (Callback callback : callbacks) {
                    if (callback instanceof NameCallback) {
                        ((NameCallback) callback).setName(username);
                    } else {
                        throw new UnsupportedCallbackException(callback);
                    }
                }
            }
        });
        loginContext.logout();

        throw new CloseShellException();
    }

    protected ConfigurationAdmin getConfigurationAdmin() {
        ServiceReference ref = getBundleContext().getServiceReference(ConfigurationAdmin.class.getName());
        if (ref == null) {
            return null;
        }
            ConfigurationAdmin admin = (ConfigurationAdmin) getBundleContext().getService(ref);
            if (admin == null) {
                return null;
            } else {
                return admin;
            }
        }



    public BundleContext getBundleContext() {
        return bundleContext;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }
}
