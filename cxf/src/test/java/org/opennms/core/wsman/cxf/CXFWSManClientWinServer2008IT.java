package org.opennms.core.wsman.cxf;

import org.opennms.core.wsman.AbstractWSManClientWinServer2008IT;
import org.opennms.core.wsman.WSManClientFactory;

public class CXFWSManClientWinServer2008IT extends AbstractWSManClientWinServer2008IT {
    @Override
    public WSManClientFactory getFactory() {
        return new CXFWSManClientFactory();
    }
}
