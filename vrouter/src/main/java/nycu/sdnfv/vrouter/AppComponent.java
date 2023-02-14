/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nycu.sdnfv.vrouter;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.jboss.netty.util.internal.ConcurrentHashMap;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.EthType.EtherType;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.routeservice.ResolvedRoute;
import org.onosproject.routeservice.RouteService;
import org.onosproject.routeservice.RouteTableId;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Timer.Context;
import com.esotericsoftware.kryo.serializers.FieldSerializer.Optional;
import com.google.common.collect.Maps;

import java.nio.file.DirectoryStream.Filter;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.swing.text.AbstractDocument.Content;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {SomeInterface.class},
           property = {
               "someProperty=Some Default String Value",
           })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */
    private String someProperty;

    private ApplicationId appId;
    private vRouterProcessor processor;
    private final QuaggaConfigListener cfgListener = new QuaggaConfigListener();
    private final ConfigFactory<ApplicationId, QuaggaConfig> factory = new ConfigFactory<ApplicationId,QuaggaConfig>(
        APP_SUBJECT_FACTORY, QuaggaConfig.class, "router") {
        @Override
        public QuaggaConfig createConfig() {
            return new QuaggaConfig();
        }
    };

    private String quagga;
    private String quaggaMac;
    private String virtualIp;
    private String virtualMac;
    private List<String> peers;

    protected Map<IpPrefix, IpAddress> nextHopIpMap = Maps.newConcurrentMap();
    protected Map<IpPrefix, MacAddress> nextHopMacMap = Maps.newConcurrentMap();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry networkConfigRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nycu.sdnfv.vrouter");
        networkConfigRegistry.addListener(cfgListener);
        networkConfigRegistry.registerConfigFactory(factory);
        // log.info("=====  " + getCP(IpAddress.valueOf("172.30.2.1")).deviceId());
        
        processor = new vRouterProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(6));
        requestIntercepts();
        
        log.info("Started======");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        networkConfigRegistry.removeListener(cfgListener);
        networkConfigRegistry.unregisterConfigFactory(factory);
        
        packetService.removeProcessor(processor);
        withdrawIntercepts();

        log.info("Stopped======");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    private void requestIntercepts() {
        packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                                    PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        packetService.cancelPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
                                    PacketPriority.REACTIVE, appId);
    }

    private ConnectPoint getCP(IpAddress ip){
        Interface intf = interfaceService.getMatchingInterface(ip);
        return intf.connectPoint();
    }

    private class QuaggaConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event){
            if((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                && event.configClass().equals(QuaggaConfig.class)) {
                    QuaggaConfig config = networkConfigRegistry.getConfig(appId, QuaggaConfig.class);
                    // log.info("hello");
                    if(config != null){
                        // quagga info
                        quagga = config.quagga();
                        quaggaMac = config.quagga_mac();
                        virtualIp = config.virtual_ip();
                        virtualMac = config.virtual_mac();
                        peers = config.peers();

                        log.info(quagga);
                        log.info(quaggaMac);
                        log.info(virtualIp);
                        log.info(virtualMac);
                        for(int i=0;i<peers.size();i++){
                            log.info(peers.get(i));
                        } 

                        // BGP traffic
                        ConnectPoint quaggaCP = new ConnectPoint(DeviceId.deviceId(quagga.substring(0,19)), PortNumber.portNumber(quagga.substring(20)));
                        FilteredConnectPoint ingressPoint = new FilteredConnectPoint(quaggaCP);

                        TrafficSelector selector = DefaultTrafficSelector.emptySelector();

                        for(String s : peers){
                            IpAddress er = IpAddress.valueOf(s);
                            IpAddress quaggaIP = interfaceService.getMatchingInterface(er).ipAddressesList().get(0).ipAddress();
                            FilteredConnectPoint egressPoint = new FilteredConnectPoint(getCP(er));    
                            
                            selector = DefaultTrafficSelector.builder()
                                        .matchEthType(Ethernet.TYPE_IPV4)
                                        .matchIPDst(IpPrefix.valueOf(er, 24))
                                        .build();
                            PointToPointIntent outBGP = PointToPointIntent.builder()
                                                        .appId(appId)
                                                        .filteredIngressPoint(ingressPoint)
                                                        .filteredEgressPoint(egressPoint)
                                                        .selector(selector)
                                                        .build();
                            intentService.submit(outBGP);
                            log.info("outBGP ");

                            selector = DefaultTrafficSelector.builder()
                                        .matchEthType(Ethernet.TYPE_IPV4)
                                        .matchIPDst(IpPrefix.valueOf(quaggaIP, 24))
                                        .build();
                            PointToPointIntent inBGP = PointToPointIntent.builder()
                                                        .appId(appId)
                                                        .filteredIngressPoint(egressPoint)
                                                        .filteredEgressPoint(ingressPoint)
                                                        .selector(selector)
                                                        .build();
                            intentService.submit(inBGP);
                            log.info("inBGP ");
                        }
                    }
                }
        }
    }

    private class vRouterProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context){
            if(context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if(ethPkt == null){
                return;
            }

            if(ethPkt.getEtherType() != Ethernet.TYPE_IPV4){
                return;
            }

            // route info
            for(RouteTableId id : routeService.getRouteTables()){
                for(ResolvedRoute info : routeService.getResolvedRoutes(id)){
                    nextHopIpMap.putIfAbsent(info.prefix(), info.nextHop());
                    nextHopMacMap.putIfAbsent(info.prefix(), info.nextHopMac());
                }
            }
            
            IPv4 iPacket = (IPv4) ethPkt.getPayload();
            IpAddress srcIp = IpAddress.valueOf(iPacket.getSourceAddress());
            IpAddress dstIp = IpAddress.valueOf(iPacket.getDestinationAddress());
            MacAddress dstMac = ethPkt.getDestinationMAC();
            ConnectPoint ingressCP = pkt.receivedFrom();

            
            FilteredConnectPoint ingressPoint;
            FilteredConnectPoint egressPoint;
            TrafficSelector selector = DefaultTrafficSelector.emptySelector();
            TrafficTreatment treatment = DefaultTrafficTreatment.emptyTreatment();
            TrafficTreatment emptyTreatment = DefaultTrafficTreatment.emptyTreatment();

            if(dstMac.equals(MacAddress.valueOf(virtualMac))){
                // SDN -> external
                IpAddress nextHopIp = null;
                MacAddress nextHopMac = null;
                for(IpPrefix item : nextHopMacMap.keySet()){
                    if(item.contains(dstIp)){
                        nextHopIp = nextHopIpMap.get(item);
                        nextHopMac = nextHopMacMap.get(item);
                        break;
                    }
                }

                if(nextHopIp != null){
                    log.info("SDN -> external");
                    context.block();

                    ingressPoint = new FilteredConnectPoint(ingressCP);
                    egressPoint = new FilteredConnectPoint(getCP(nextHopIp));

                    selector = DefaultTrafficSelector.builder()
                                                    .matchEthType(Ethernet.TYPE_IPV4)
                                                    .matchIPDst(IpPrefix.valueOf(dstIp, 24))
                                                    .build();
                    treatment = DefaultTrafficTreatment.builder()
                                                    .setEthSrc(MacAddress.valueOf(quaggaMac))
                                                    .setEthDst(nextHopMac)
                                                    .build();

                    PointToPointIntent intent = PointToPointIntent.builder()
                                                                    .appId(appId)
                                                                    .filteredIngressPoint(ingressPoint)
                                                                    .filteredEgressPoint(egressPoint)
                                                                    .selector(selector)
                                                                    .treatment(treatment)
                                                                    .build();
                    intentService.submit(intent);
                    log.info("SDN-ex intent");
                    log.info(ingressPoint.toString() + " -> " + egressPoint.toString());
                    
                    context.treatmentBuilder().addTreatment(emptyTreatment);
                    context.send();
                }

            }else if(dstMac.equals(MacAddress.valueOf(quaggaMac))){
                // check whether send to SDN or just transit
                Boolean isToExternal = false;
                for(IpPrefix item : nextHopIpMap.keySet()){
                    if(item.contains(dstIp)){
                        isToExternal = true;
                        break;
                    }
                }

                log.info("istoexternal: " + isToExternal);

                if(isToExternal == false){
                    // check whether dst is in SDN
                    Boolean hasHost = false;
                    ConnectPoint host = new ConnectPoint(null, null);
                    MacAddress hostMac = MacAddress.ZERO;

                    for(Host h : hostService.getHostsByIp(dstIp)){
                        if(h != null){
                            host = ConnectPoint.deviceConnectPoint(h.location().toString());
                            hostMac = h.mac();
                            hasHost = true;
                            break;
                        } 
                    }

                    if(hasHost){
                        // external -> SDN

                        log.info("external -> SDN");
                        context.block();

                        ingressPoint = new FilteredConnectPoint(ingressCP);
                        egressPoint = new FilteredConnectPoint(host);

                        selector = DefaultTrafficSelector.builder()
                                                        .matchEthType(Ethernet.TYPE_IPV4)
                                                        .matchIPDst(IpPrefix.valueOf(dstIp, 24))
                                                        .build();
                        
                        treatment = DefaultTrafficTreatment.builder()
                                                        .setEthSrc(MacAddress.valueOf(virtualMac))
                                                        .setEthDst(hostMac)
                                                        .build();

                        PointToPointIntent intent = PointToPointIntent.builder()
                                                                        .appId(appId)
                                                                        .filteredIngressPoint(ingressPoint)
                                                                        .filteredEgressPoint(egressPoint)
                                                                        .selector(selector)
                                                                        .treatment(treatment)
                                                                        .build();
                        intentService.submit(intent);
                        log.info("ex-SDN intent");
                        log.info(ingressPoint.toString() + " -> " + egressPoint.toString());

                        context.treatmentBuilder().addTreatment(emptyTreatment);
                        context.send();
                    }
                    
                }else{
                    // external <-> external
                    log.info("external <-> external");
                    context.block();

                    IpAddress nextHopIp = null;
                    MacAddress nextHopMac = null;
                    for(IpPrefix item : nextHopMacMap.keySet()){
                        if(item.contains(dstIp)){
                            nextHopIp = nextHopIpMap.get(item);
                            nextHopMac = nextHopMacMap.get(item);
                            break;
                        }
                    }
                    
                    Set<FilteredConnectPoint> multiCP = new HashSet<>();
                    for(String ipString : peers){
                        if(!nextHopIp.toString().equals(ipString)){
                            FilteredConnectPoint peerCP = new FilteredConnectPoint(getCP(IpAddress.valueOf(ipString)));
                            multiCP.add(peerCP);
                        }
                    }
                    egressPoint = new FilteredConnectPoint(getCP(nextHopIp));

                    selector = DefaultTrafficSelector.builder()
                                                    .matchEthType(Ethernet.TYPE_IPV4)
                                                    .matchIPDst(IpPrefix.valueOf(dstIp, 24))
                                                    .build();

                    treatment = DefaultTrafficTreatment.builder()
                                                    .setEthSrc(MacAddress.valueOf(quaggaMac))
                                                    .setEthDst(nextHopMac)
                                                    .build();

                    MultiPointToSinglePointIntent intent = MultiPointToSinglePointIntent.builder()
                                                                                        .appId(appId)
                                                                                        .filteredIngressPoints(multiCP)
                                                                                        .filteredEgressPoint(egressPoint)
                                                                                        .selector(selector)
                                                                                        .treatment(treatment)
                                                                                        .build();
                    intentService.submit(intent);
                    log.info("ex-ex intent");
                    log.info(multiCP.toString() + " -> " + egressPoint.toString());
                    log.info(intent.toString());

                    context.treatmentBuilder().addTreatment(emptyTreatment);
                    context.send();
                }
            }
            

        }
    }


}
