// MatchIntf: interface remota do servidor de match
// json-in : {"ip": "x.x.x.x", "porta": int, "protocolo": "tcp"|"udp"}
// json-ret: {"ip": "x.x.x.x", "porta": int, "inicia": true|false}

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface MatchIntf extends Remote {
    String getOponent(String jsonIn) throws RemoteException, TimeoutMatchEx;
}
