/**
 * Transport Negotiator
 * Automatically selects the best transport method (WebRTC or WebSocket)
 */
class TransportNegotiator {
    constructor(serverHost, serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.baseUrl = `http://${serverHost}:${serverPort}`;
    }

    async negotiate() {
        console.log('TransportNegotiator: Starting negotiation');

        try {
            // 1. Check client capabilities
            const clientCaps = this.getClientCapabilities();
            console.log('TransportNegotiator: Client capabilities:', JSON.stringify(clientCaps, null, 2));

            // 2. Get server capabilities
            const serverCaps = await this.getServerCapabilities();
            console.log('TransportNegotiator: Server capabilities:', JSON.stringify(serverCaps, null, 2));

            // 3. Select best transport
            const transport = this.selectTransport(clientCaps, serverCaps);
            console.log('TransportNegotiator: Selected transport:', transport);
            console.log('TransportNegotiator: Selection reason:', {
                clientWebRTC: clientCaps.webrtc,
                serverWebRTC: serverCaps.webrtcSupported,
                network: clientCaps.network,
                shouldUseWebRTC: clientCaps.webrtc && serverCaps.webrtcSupported && clientCaps.network !== 'poor'
            });

            // 4. Get transport configuration
            const config = await this.getTransportConfig(transport);

            return {
                transport,
                config
            };

        } catch (error) {
            console.error('TransportNegotiator: Negotiation failed:', error);

            // Fallback to WebSocket
            return {
                transport: 'websocket',
                config: {
                    websocket_url: `ws://${this.serverHost}:${this.serverPort}/remote`
                }
            };
        }
    }

    getClientCapabilities() {
        return {
            webrtc: this.isWebRTCSupported(),
            websocket: true,
            network: this.estimateNetworkQuality()
        };
    }

    isWebRTCSupported() {
        return !!(window.RTCPeerConnection &&
                  window.RTCSessionDescription &&
                  window.RTCIceCandidate);
    }

    estimateNetworkQuality() {
        // Use Network Information API if available
        const connection = navigator.connection ||
                          navigator.mozConnection ||
                          navigator.webkitConnection;

        if (connection && connection.downlink) {
            const downlink = connection.downlink; // Mbps

            if (downlink > 10) return 'excellent';
            if (downlink > 5) return 'good';
            if (downlink > 2) return 'fair';
            return 'poor';
        }

        // Default to good if API not available
        return 'good';
    }

    async getServerCapabilities() {
        const response = await fetch(`${this.baseUrl}/screen/capabilities`);

        if (!response.ok) {
            throw new Error('Failed to get server capabilities');
        }

        const result = await response.json();
        return result.data;
    }

    selectTransport(clientCaps, serverCaps) {
        // Prefer WebRTC if both client and server support it
        // WebRTC has adaptive bitrate, so it can handle poor network conditions
        if (clientCaps.webrtc && serverCaps.webrtcSupported) {
            console.log('TransportNegotiator: Using WebRTC (network quality:', clientCaps.network + ')');
            return 'webrtc';
        }

        // Fallback to WebSocket if WebRTC is not supported
        console.log('TransportNegotiator: Falling back to WebSocket');
        return 'websocket';
    }

    async getTransportConfig(transport) {
        const response = await fetch(`${this.baseUrl}/screen/negotiate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                preferred: transport,
                fallback: 'websocket'
            })
        });

        if (!response.ok) {
            throw new Error('Failed to negotiate transport');
        }

        const result = await response.json();
        return result.data;
    }
}
