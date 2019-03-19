/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.features.internal.model.processing;

import java.util.LinkedList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;

import org.apache.karaf.features.LocationPattern;

@XmlType(name = "bundleProcessing", propOrder = {
        "processing"
})
@XmlAccessorType(XmlAccessType.FIELD)
public class BundleProcessing {

    @XmlElement(name = "bundle")
    private List<ProcessBundle> processing = new LinkedList<>();

    public List<ProcessBundle> getProcessing() {
        return processing;
    }

    @XmlType(name = "processBundle", propOrder = {
            "addHeaders",
            "removeHeaders",
            "replaceHeaders",
            "clauses"
    })
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class ProcessBundle {
        @XmlAttribute
        private String location;
        @XmlAttribute
        private String match;
        @XmlTransient
        private LocationPattern locationPattern;

        @XmlElement(name = "add")
        private List<AddHeader> addHeaders = new LinkedList<>();
        @XmlElement(name = "remove")
        private List<RemoveHeader> removeHeaders = new LinkedList<>();
        @XmlElement(name = "replace")
        private List<ReplaceHeader> replaceHeaders = new LinkedList<>();
        @XmlElement(name = "clause")
        private List<AlterClause> clauses = new LinkedList<>();

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public LocationPattern getLocationPattern() {
            return locationPattern;
        }

        public List<AddHeader> getAddHeaders() {
            return addHeaders;
        }

        public List<RemoveHeader> getRemoveHeaders() {
            return removeHeaders;
        }

        public List<ReplaceHeader> getReplaceHeaders() {
            return replaceHeaders;
        }

        public List<AlterClause> getClauses() {
            return clauses;
        }

        /**
         * Changes String for <code>originalUri</code> into {@link LocationPattern}
         */
        public void compile() {
            if (location != null) {
                locationPattern = new LocationPattern(location);
            }
        }
    }

    @XmlType(name = "processBundleAddHeader")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class AddHeader {
        @XmlAttribute
        private String header;
        @XmlAttribute
        private String value;

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @XmlType(name = "processBundleRemoveHeader")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class RemoveHeader {
        @XmlAttribute
        private String header;

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }
    }

    @XmlType(name = "processBundleReplaceHeader")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class ReplaceHeader {
        @XmlAttribute
        private String header;
        @XmlAttribute
        private String value;

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @XmlType(name = "processBundleAlterClause")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class AlterClause {
        @XmlAttribute
        private String header;
        @XmlAttribute
        private String name;
        @XmlAttribute
        private String value;

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

}
