// Android interface list without netlink.
//
// Since Android 11, an ordinary app UID cannot bind a NETLINK_ROUTE
// socket, so Go's net.Interfaces() fails on Android with
// "route ip+net: netlinkrib: permission denied" (golang/go#40569).
// Tailscale's netmon calls net.Interfaces() while tsnet is starting
// (netmon.New -> interfaceState), so tsnet.Up() fails with exactly
// that error — even with a valid auth key.
//
// Tailscale provides an escape hatch for this: netmon.RegisterInterfaceGetter.
// The official Android app uses it; here we implement the getter with
// only things an unprivileged app may use:
//   - interface names / index / MTU / MAC / up-state from /sys/class/net/*
//   - IPv4 address + netmask via SIOCGIFADDR / SIOCGIFNETMASK ioctls
//     on a plain AF_INET datagram socket (the same approach as
//     github.com/wlynxg/anet)
//   - IPv6 addresses from /proc/net/if_inet6
//
// Addresses are returned as netmon.Interface.AltAddrs, so netmon never
// calls net.Interface.Addrs() (which would use netlink again).
//
// Registered only on Android (runtime.GOOS == "android"); on other
// platforms the standard net.Interfaces() path is used.
package tsnetmobile

import (
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"

	"golang.org/x/sys/unix"
	"tailscale.com/net/netmon"
)

var registerAndroidIfacesOnce sync.Once

// registerAndroidInterfaceGetter installs the netlink-free interface
// getter. Safe to call repeatedly; only the first call registers.
func registerAndroidInterfaceGetter() {
	if runtime.GOOS != "android" {
		return
	}
	registerAndroidIfacesOnce.Do(func() {
		netmon.RegisterInterfaceGetter(androidInterfaces)
	})
}

func readSysfsInt(path string) (int, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(b)))
}

func readSysfsString(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

// ipv6AddrsByIndex parses /proc/net/if_inet6 into ifindex -> []net.Addr.
// Each line is: <32 hex addr> <ifindex hex> <prefixlen hex> <scope hex>
// <flags hex> <iface name>.
func ipv6AddrsByIndex() map[int][]net.Addr {
	out := make(map[int][]net.Addr)
	b, err := os.ReadFile("/proc/net/if_inet6")
	if err != nil {
		return out
	}
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) < 6 {
			continue
		}
		var raw [16]byte
		ok := true
		for i := 0; i < 16; i++ {
			v, err := strconv.ParseUint(f[0][i*2:i*2+2], 16, 8)
			if err != nil {
				ok = false
				break
			}
			raw[i] = byte(v)
		}
		if !ok {
			continue
		}
		idx, err := strconv.ParseInt(f[1], 16, 32)
		if err != nil {
			continue
		}
		bits, err := strconv.Atoi(f[2])
		if err != nil || bits < 0 || bits > 128 {
			bits = 128
		}
		ip := netip.AddrFrom16(raw)
		out[int(idx)] = append(out[int(idx)], &net.IPNet{
			IP:   ip.AsSlice(),
			Mask: net.CIDRMask(bits, 128),
		})
	}
	return out
}

// androidInterfaces lists the device's interfaces without netlink.
func androidInterfaces() ([]netmon.Interface, error) {
	entries, err := os.ReadDir("/sys/class/net")
	if err != nil {
		return nil, fmt.Errorf("read /sys/class/net: %w", err)
	}
	v6 := ipv6AddrsByIndex()

	// One datagram socket for the SIOCGIFADDR / SIOCGIFNETMASK ioctls.
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM, 0)
	if err != nil {
		fd = -1
	} else {
		defer unix.Close(fd)
	}

	var out []netmon.Interface
	for _, e := range entries {
		name := e.Name()
		dir := filepath.Join("/sys/class/net", name)
		idx, _ := readSysfsInt(filepath.Join(dir, "ifindex"))
		mtu, _ := readSysfsInt(filepath.Join(dir, "mtu"))

		var flags net.Flags
		switch readSysfsString(filepath.Join(dir, "operstate")) {
		case "up", "unknown":
			flags |= net.FlagUp
		}
		if name == "lo" {
			flags |= net.FlagLoopback
		} else {
			flags |= net.FlagBroadcast | net.FlagMulticast
		}

		var hw net.HardwareAddr
		if macStr := readSysfsString(filepath.Join(dir, "address")); macStr != "" {
			if mac, err := net.ParseMAC(macStr); err == nil {
				zero := true
				for _, b := range mac {
					if b != 0 {
						zero = false
						break
					}
				}
				if !zero {
					hw = mac
				}
			}
		}

		var addrs []net.Addr
		if fd >= 0 {
			ifr, err := unix.NewIfreq(name)
			if err == nil {
				if err := unix.IoctlIfreq(fd, unix.SIOCGIFADDR, ifr); err == nil {
					if ipb, err := ifr.Inet4Addr(); err == nil {
						ip := net.IP(ipb)
						ones, bits := 32, 32
						if ifr2, err := unix.NewIfreq(name); err == nil {
							if err := unix.IoctlIfreq(fd, unix.SIOCGIFNETMASK, ifr2); err == nil {
								if maskb, err := ifr2.Inet4Addr(); err == nil {
									ones, bits = net.IPMask(maskb).Size()
								}
							}
						}
						addrs = append(addrs, &net.IPNet{IP: ip, Mask: net.CIDRMask(ones, bits)})
					}
				}
			}
		}
		addrs = append(addrs, v6[idx]...)

		out = append(out, netmon.Interface{
			Interface: &net.Interface{
				Index:        idx,
				MTU:          mtu,
				Name:         name,
				HardwareAddr: hw,
				Flags:        flags,
			},
			AltAddrs: addrs,
		})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no interfaces found in /sys/class/net")
	}
	return out, nil
}
