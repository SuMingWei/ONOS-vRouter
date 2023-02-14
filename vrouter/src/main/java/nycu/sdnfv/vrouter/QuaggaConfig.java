package nycu.sdnfv.vrouter;

import java.util.List;
import java.util.function.Function;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

public class QuaggaConfig extends Config<ApplicationId> {
    public static final String QUAGGA = "quagga";
    public static final String QUAGGA_MAC = "quagga-mac";
    public static final String VIRTUAL_IP = "virtual-ip";
    public static final String VIRTUAL_MAC = "virtual-mac";
    public static final String PEERS = "peers";

    @Override
    public boolean isValid(){
        return true;
    }

    public String quagga(){
        return get(QUAGGA, null);
    }

    public String quagga_mac(){
        return get(QUAGGA_MAC, null);
    }

    public String virtual_ip(){
        return get(VIRTUAL_IP, null);
    }

    public String virtual_mac(){
        return get(VIRTUAL_MAC, null);
    }

    Function<String, String> func = a -> a;

    public List<String> peers(){
        return getList(PEERS, func);
    }
}
