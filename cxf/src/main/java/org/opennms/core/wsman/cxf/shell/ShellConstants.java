/*
 * Copyright (C) The OpenNMS Group
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
package org.opennms.core.wsman.cxf.shell;

/**
 * Wire-protocol constants for WinRS shell operations (MS-WSMV §3.1.4.10–11).
 */
public final class ShellConstants {

    private ShellConstants() {}

    /** Resource URI of the WinRS cmd.exe shell. */
    public static final String SHELL_RESOURCE_URI =
        "http://schemas.microsoft.com/wbem/wsman/1/windows/shell/cmd";

    /** Namespace for the {@code rsp:} shell elements. */
    public static final String NS_SHELL =
        "http://schemas.microsoft.com/wbem/wsman/1/windows/shell";

    // --- WS-Addressing Action URIs ---------------------------------------------------

    public static final String ACTION_CREATE =
        "http://schemas.xmlsoap.org/ws/2004/09/transfer/Create";

    public static final String ACTION_DELETE =
        "http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete";

    public static final String ACTION_COMMAND       = NS_SHELL + "/Command";
    public static final String ACTION_RECEIVE       = NS_SHELL + "/Receive";
    public static final String ACTION_SEND          = NS_SHELL + "/Send";
    public static final String ACTION_SIGNAL        = NS_SHELL + "/Signal";

    // --- OptionSet keys (per MS-WSMV §2.2.4.1) ---------------------------------------

    public static final String OPT_NO_PROFILE       = "WINRS_NOPROFILE";
    public static final String OPT_CODEPAGE         = "WINRS_CODEPAGE";
    public static final String OPT_CONSOLEMODE_STDIN = "WINRS_CONSOLEMODE_STDIN";
    public static final String OPT_SKIP_CMD_SHELL   = "WINRS_SKIP_CMD_SHELL";

    // --- Signal codes (rsp:Code values) ----------------------------------------------

    public static final String SIGNAL_TERMINATE     = NS_SHELL + "/signal/terminate";
    public static final String SIGNAL_CTRL_C        = NS_SHELL + "/signal/ctrl_c";
    public static final String SIGNAL_CTRL_BREAK    = NS_SHELL + "/signal/ctrl_break";

    // --- Command states (rsp:CommandState State attribute) ---------------------------

    public static final String STATE_PENDING        = NS_SHELL + "/CommandState/Pending";
    public static final String STATE_RUNNING        = NS_SHELL + "/CommandState/Running";
    public static final String STATE_DONE           = NS_SHELL + "/CommandState/Done";

    // --- Stream names ----------------------------------------------------------------

    public static final String STREAM_STDIN         = "stdin";
    public static final String STREAM_STDOUT        = "stdout";
    public static final String STREAM_STDERR       = "stderr";
}