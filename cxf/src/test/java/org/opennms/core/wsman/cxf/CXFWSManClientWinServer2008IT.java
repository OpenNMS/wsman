package org.opennms.core.wsman.cxf;

import org.junit.Ignore;
import org.opennms.core.wsman.AbstractWSManClientWinServer2008IT;
import org.opennms.core.wsman.WSManClientFactory;

@Ignore("Expects an Windows 2k8 Service to be configured in ~/wsman.properties")
public class CXFWSManClientWinServer2008IT extends AbstractWSManClientWinServer2008IT {
    @Override
    public WSManClientFactory getFactory() {
        return new CXFWSManClientFactory();
    }
}
