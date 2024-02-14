# Testbed application for MANETs

Opportunistic Mobile Ad Hoc Networks (MANETs) offer a versatile solution in contexts where the Internet is unavailable. These networks facilitate transmissions between endpoints using a store-carry-forward strategy, allowing information to be stored during periods of disconnection. Consequently, selecting the next hop in the routing process becomes a significant challenge for nodes, particularly due to its impact on Quality of Service (QoS). Routing strategies are therefore crucial in opportunistic MANETs, but their deployment and evaluation in real scenarios can be challenging. In response to this context, this paper introduces a monitoring software-driven tool designed to evaluate the QoS of routing algorithms in physical opportunistic MANETs. The implementation and its components are detailed, along with a case study and the outcomes provided by an implementation of the proposed solution. The results demonstrate the effectiveness of the proposed tool in enabling the analysis of routing protocols in real scenarios, highlighting significant differences with simulation results: mobility patterns in simulations tend to be inaccurate and overly optimistic, leading to a higher delivery probability and lower latency than what is observed in the real testbed.

## Proof-of-concept configuration.
The current version of the application is a proof-of-concept, partially based on the advances from https://github.com/aarmea/noise. To use the application, the following steps should be taken:
1. Install the application on the physical smartphones that will serve as nodes in the MANET.
2. Run the application on the smartphones to initiate monitoring and communication features.
3. Access QoS reports by checking the internal storage of the devices. These reports provide detailed QoS information about the MANET, enabling comprehensive analysis of events and supplying a reliable dataset for training machine learning models.

## Legal

### Cryptography notice

This distribution includes cryptographic software. The country in which you currently reside may have restrictions on the import, possession, use, and/or re-export to another country, of encryption software.
BEFORE using any encryption software, please check your country's laws, regulations and policies concerning the import, possession, or use, and re-export of encryption software, to see if this is permitted.
See <http://www.wassenaar.org/> for more information.

The U.S. Government Department of Commerce, Bureau of Industry and Security (BIS), has classified this software as Export Commodity Control Number (ECCN) 5D002.C.1, which includes information security software using or performing cryptographic functions with asymmetric algorithms.
The form and manner of this distribution makes it eligible for export under the License Exception ENC Technology Software Unrestricted (TSU) exception (see the BIS Export Administration Regulations, Section 740.13) for both object code and source code.

### Experimental software

This project has not been reviewed by a security expert. If you entrust Test Application for MANETs with sensitive information, you are doing so at your own risk.

### License

You may use Testbed Application for MANETs under the terms of the MIT license. See LICENSE for more details.
