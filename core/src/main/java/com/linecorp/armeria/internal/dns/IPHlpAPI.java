/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Copyright (c) 1998-2011, Brian Wellington.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *
 *   * Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.internal.dns;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

interface IPHlpAPI extends Library {
    IPHlpAPI INSTANCE = Native.load("IPHlpAPI", IPHlpAPI.class, W32APIOptions.ASCII_OPTIONS);

    int AF_UNSPEC = 0;
    int AF_INET = 2;
    int AF_INET6 = 23;

    int GAA_FLAG_SKIP_UNICAST = 0x0001;
    int GAA_FLAG_SKIP_ANYCAST = 0x0002;
    int GAA_FLAG_SKIP_MULTICAST = 0x0004;
    int GAA_FLAG_SKIP_DNS_SERVER = 0x0008;
    int GAA_FLAG_INCLUDE_PREFIX = 0x0010;
    int GAA_FLAG_SKIP_FRIENDLY_NAME = 0x0020;
    int GAA_FLAG_INCLUDE_WINS_INFO = 0x0040;
    int GAA_FLAG_INCLUDE_GATEWAYS = 0x0080;
    int GAA_FLAG_INCLUDE_ALL_INTERFACES = 0x0100;
    int GAA_FLAG_INCLUDE_ALL_COMPARTMENTS = 0x0200;
    int GAA_FLAG_INCLUDE_TUNNEL_BINDINGORDER = 0x0400;

    @Structure.FieldOrder({"sin_family", "sin_port", "sin_addr", "sin_zero"})
    class sockaddr_in extends Structure {
        public sockaddr_in(Pointer p) {
            super(p);
            read();
        }

        public short sin_family;
        public short sin_port;
        public byte[] sin_addr = new byte[4];
        public byte[] sin_zero = new byte[8];
    }

    @Structure.FieldOrder({"sin6_family", "sin6_port", "sin6_flowinfo", "sin6_addr", "sin6_scope_id"})
    class sockaddr_in6 extends Structure {
        public sockaddr_in6(Pointer p) {
            super(p);
            read();
        }

        public short sin6_family;
        public short sin6_port;
        public int sin6_flowinfo;
        public byte[] sin6_addr = new byte[16];
        public int sin6_scope_id;
    }

    @Structure.FieldOrder({"lpSockaddr", "iSockaddrLength"})
    class SOCKET_ADDRESS extends Structure {
        public Pointer lpSockaddr;
        public int iSockaddrLength;

        InetAddress toAddress() throws UnknownHostException {
            switch (lpSockaddr.getShort(0)) {
                case AF_INET:
                    sockaddr_in in4 = new sockaddr_in(lpSockaddr);
                    return InetAddress.getByAddress(in4.sin_addr);
                case AF_INET6:
                    sockaddr_in6 in6 = new sockaddr_in6(lpSockaddr);
                    return Inet6Address.getByAddress("", in6.sin6_addr, in6.sin6_scope_id);
            }

            return null;
        }
    }

    @Structure.FieldOrder({
            "Length",
            "IfIndex",
            "Next",
            "Address",
            "PrefixOrigin",
            "SuffixOrigin",
            "DadState",
            "ValidLifetime",
            "PreferredLifetime",
            "LeaseLifetime",
            "OnLinkPrefixLength"
    })
    class IP_ADAPTER_UNICAST_ADDRESS_LH extends Structure {
        public static class ByReference extends IP_ADAPTER_UNICAST_ADDRESS_LH
                implements Structure.ByReference {}

        public int Length;
        public int IfIndex;

        public IP_ADAPTER_UNICAST_ADDRESS_LH.ByReference Next;
        public SOCKET_ADDRESS Address;
        public int PrefixOrigin;
        public int SuffixOrigin;
        public int DadState;
        public int ValidLifetime;
        public int PreferredLifetime;
        public int LeaseLifetime;
        public byte OnLinkPrefixLength;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_DNS_SERVER_ADDRESS_XP extends Structure {
        public static class ByReference extends IP_ADAPTER_DNS_SERVER_ADDRESS_XP
                implements Structure.ByReference {}

        public int Length;
        public int Reserved;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_ANYCAST_ADDRESS_XP extends Structure {
        public static class ByReference extends IP_ADAPTER_ANYCAST_ADDRESS_XP
                implements Structure.ByReference {}

        public int Length;
        public int Reserved;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Length", "Reserved", "Next", "Address"})
    class IP_ADAPTER_MULTICAST_ADDRESS_XP extends Structure {
        public static class ByReference extends IP_ADAPTER_MULTICAST_ADDRESS_XP
                implements Structure.ByReference {}

        public int Length;
        public int Reserved;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference Next;
        public SOCKET_ADDRESS Address;
    }

    @Structure.FieldOrder({"Next", "_String"})
    class IP_ADAPTER_DNS_SUFFIX extends Structure {
        public static class ByReference extends IP_ADAPTER_DNS_SUFFIX
                implements Structure.ByReference {}

        public IP_ADAPTER_DNS_SUFFIX.ByReference Next;
        public char[] _String = new char[256];
    }

    @Structure.FieldOrder({
            "Length",
            "IfIndex",
            "Next",
            "AdapterName",
            "FirstUnicastAddress",
            "FirstAnycastAddress",
            "FirstMulticastAddress",
            "FirstDnsServerAddress",
            "DnsSuffix",
            "Description",
            "FriendlyName",
            "PhysicalAddress",
            "PhysicalAddressLength",
            "Flags",
            "Mtu",
            "IfType",
            "OperStatus",
            "Ipv6IfIndex",
            "ZoneIndices",
            "FirstPrefix",
            "TransmitLinkSpeed",
            "ReceiveLinkSpeed",
            "FirstWinsServerAddress",
            "FirstGatewayAddress",
            "Ipv4Metric",
            "Ipv6Metric",
            "Luid",
            "Dhcpv4Server",
            "CompartmentId",
            "NetworkGuid",
            "ConnectionType",
            "TunnelType",
            "Dhcpv6Server",
            "Dhcpv6ClientDuid",
            "Dhcpv6ClientDuidLength",
            "Dhcpv6Iaid",
            "FirstDnsSuffix",
    })
    class IP_ADAPTER_ADDRESSES_LH extends Structure {
        public static class ByReference extends IP_ADAPTER_ADDRESSES_LH
                implements Structure.ByReference {}

        public IP_ADAPTER_ADDRESSES_LH(Pointer p) {
            super(p);
            read();
        }

        public IP_ADAPTER_ADDRESSES_LH() {}

        public int Length;
        public int IfIndex;

        public IP_ADAPTER_ADDRESSES_LH.ByReference Next;
        public String AdapterName;
        public IP_ADAPTER_UNICAST_ADDRESS_LH.ByReference FirstUnicastAddress;
        public IP_ADAPTER_ANYCAST_ADDRESS_XP.ByReference FirstAnycastAddress;
        public IP_ADAPTER_MULTICAST_ADDRESS_XP.ByReference FirstMulticastAddress;
        public IP_ADAPTER_DNS_SERVER_ADDRESS_XP.ByReference FirstDnsServerAddress;
        public WString DnsSuffix;
        public WString Description;
        public WString FriendlyName;
        public byte[] PhysicalAddress = new byte[8];
        public int PhysicalAddressLength;
        public int Flags;
        public int Mtu;
        public int IfType;
        public int OperStatus;
        public int Ipv6IfIndex;
        public int[] ZoneIndices = new int[16];
        public Pointer FirstPrefix;
        public long TransmitLinkSpeed;
        public long ReceiveLinkSpeed;
        public Pointer FirstWinsServerAddress;
        public Pointer FirstGatewayAddress;
        public int Ipv4Metric;
        public int Ipv6Metric;
        public Pointer Luid;
        public SOCKET_ADDRESS Dhcpv4Server;
        public int CompartmentId;
        public Guid.GUID NetworkGuid;
        public int ConnectionType;
        public int TunnelType;
        public SOCKET_ADDRESS Dhcpv6Server;
        public byte[] Dhcpv6ClientDuid = new byte[130];
        public int Dhcpv6ClientDuidLength;
        public int Dhcpv6Iaid;
        public IP_ADAPTER_DNS_SUFFIX.ByReference FirstDnsSuffix;
    }

    int GetAdaptersAddresses(
            int family,
            int flags,
            Pointer reserved,
            Pointer adapterAddresses,
            IntByReference sizePointer);
}
