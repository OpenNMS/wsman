/*
 * Copyright 2015, The OpenNMS Group
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opennms.core.wsman;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

import org.kohsuke.args4j.Localizable;

public enum Messages implements Localizable {
    NO_ARGUMENT;

    @Override
    public String formatWithLocale(Locale locale, Object... args) {
        ResourceBundle localized = ResourceBundle.getBundle(Messages.class.getName(), locale);
        return MessageFormat.format(localized.getString(name()),args);
    }

    @Override
    public String format(Object... args) {
        return formatWithLocale(Locale.getDefault(),args);
    }
}
