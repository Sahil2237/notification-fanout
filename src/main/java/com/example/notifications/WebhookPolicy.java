package com.example.notifications;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;

/** Approved origins plus address checks on the actual HTTP connection lookup. */
public final class WebhookPolicy {
    private final java.util.function.Predicate<String> approved;
    private final boolean allowLocal;
    @FunctionalInterface interface AddressLookup { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final AddressLookup lookup;
    public WebhookPolicy(ServiceConfiguration config) {
        this(config, config.allowedWebhookOrigins.stream().map(URI::create).map(WebhookPolicy::origin)
                .collect(Collectors.toUnmodifiableSet())::contains);
    }
    public WebhookPolicy(ServiceConfiguration config, java.util.function.Predicate<String> approved) {
        this(config,approved,InetAddress::getAllByName);
    }
    WebhookPolicy(ServiceConfiguration config, java.util.function.Predicate<String> approved,AddressLookup lookup) {
        allowLocal=config.allowLocalWebhooks;
        this.approved=approved;
        this.lookup=lookup;
    }
    public URI validate(String value) {
        var uri=URI.create(value);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || allowLocal && "http".equals(uri.getScheme()))
                || !approved.test(origin(uri))) throw new IllegalArgumentException("Webhook destination is not approved");
        try { checkedAddresses(uri.getHost()); }
        catch(UnknownHostException e){throw new IllegalArgumentException("Webhook destination does not resolve to approved addresses");}
        return uri;
    }
    public String validateOrigin(String value) {
        var uri=URI.create(value);
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || uri.getPort() < -1 || uri.getPort()==0 || uri.getPort()>65535
                || !("https".equals(uri.getScheme()) || allowLocal && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Supply a public HTTPS origin without a path, query or credentials");
        }
        try {checkedAddresses(uri.getHost());}
        catch (UnknownHostException error) {throw new IllegalArgumentException("Origin does not resolve to public addresses");}
        return origin(uri);
    }
    public DnsResolver resolver() {
        return new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {return checkedAddresses(host);}
            @Override public String resolveCanonicalHostname(String host) {return host;}
        };
    }
    private InetAddress[] checkedAddresses(String host) throws UnknownHostException {
        var addresses=lookup.resolve(host);
        for(var address:addresses) if(!allowLocal && !publicAddress(address)) throw new UnknownHostException("Non-public destination blocked");
        return addresses;
    }
    static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        var b=address.getAddress();
        if(b.length==4) {
            var a=b[0]&255;var c=b[1]&255;var d=b[2]&255;
            return !(a==0 || a==10 || a==127 || a>=224 || a==100 && c>=64 && c<=127
                || a==169 && c==254 || a==172 && c>=16 && c<=31
                || a==192 && (c==168 || c==0 || c==2)
                || a==198 && (c==18 || c==19 || c==51 && d==100)
                || a==203 && c==0 && d==113);
        }
        // Restrict IPv6 to global unicast and exclude documentation/tunneling ranges.
        return (b[0]&0xe0)==0x20 && !((b[0]&255)==0x20 && (b[1]&255)==0x02)
            && !((b[0]&255)==0x20 && (b[1]&255)==0x01 && (b[2]&255)==0x0d && (b[3]&255)==0xb8);
    }
    static String origin(URI uri) {
        var port=uri.getPort()==-1?("https".equals(uri.getScheme())?443:80):uri.getPort();
        return uri.getScheme()+"://"+(uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT))+":"+port;
    }
}
