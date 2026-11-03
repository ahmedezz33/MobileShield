cmake_minimum_required(VERSION 3.18.1)

# Packet engine from NetGuard (GPLv3, https://github.com/M66B/NetGuard)
project(netguard C)

add_library(netguard SHARED
        netguard/netguard.c
        netguard/session.c
        netguard/ip.c
        netguard/tcp.c
        netguard/udp.c
        netguard/icmp.c
        netguard/tls.c
        netguard/dns.c
        netguard/dhcp.c
        netguard/pcap.c
        netguard/util.c)

include_directories(netguard)

find_library(log-lib log)
target_link_libraries(netguard ${log-lib})
target_link_options(netguard PRIVATE "-Wl,-z,max-page-size=16384")
