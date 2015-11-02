/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.itests;

import junit.framework.Assert;

import org.junit.Test;

/**
 * This test exercises the Shell Command ACL for the jaas scope commands as defined in
 * apache-karaf/src/main/distribution/text/etc/org.apache.karaf.command.acl.jaas.cfg
 */
public class JaasSshCommandSecurityTest extends SshCommandTestBase {
    @Test
    public void testJaasCommandSecurityViaSsh() throws Exception {
        String vieweruser = "viewer" + System.nanoTime() + "_jaas";

        addViewer(vieweruser);

        String userName = "XXX" + System.nanoTime();
        assertCommand(vieweruser, "jaas:manage --realm karaf;" +
        		"jaas:useradd " + userName + " pwd;" +
				"jaas:update", Result.NO_CREDENTIALS);
        String r = assertCommand(vieweruser, "jaas:manage --realm karaf;" +
				"jaas:users", Result.NO_CREDENTIALS);
        Assert.assertFalse("The viewer should not have the credentials to add the new user",
                r.contains(userName));

        assertCommand("karaf", "jaas:manage --realm karaf;" +
                "jaas:useradd " + userName + " pwd;" +
                "jaas:update", Result.OK);
        String r2 = assertCommand("karaf", "jaas:manage --realm karaf;" +
                "jaas:users", Result.OK);
        Assert.assertTrue("The admin user should have the rights to add the new user",
                r2.contains(userName));
    }
}
