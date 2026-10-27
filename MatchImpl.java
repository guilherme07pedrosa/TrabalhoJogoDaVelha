// MatchImpl: servidor de match (RMI).
// O RMI atende cada chamada remota numa thread propria. Quando ainda nao ha
// Uso: java -Djava.rmi.server.hostname=<IP desta maquina> MatchImpl <portaRegistry> [timeoutSegundos]

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Iterator;
import java.util.LinkedList;

public class MatchImpl implements MatchIntf {

    // chamada getOponent aguardando oponente
    private static class Pedido {
        final String ip, protocolo;
        final int porta;
        String resposta; // e preenchida quando o par e formado

        Pedido(String ip, int porta, String protocolo) {
            this.ip = ip; this.porta = porta; this.protocolo = protocolo;
        }

        public String toString() { return ip + ":" + porta + "/" + protocolo; }
    }

    private final LinkedList<Pedido> fila = new LinkedList<>();
    private final long timeoutMs;

    public MatchImpl(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public synchronized String getOponent(String jsonIn) throws RemoteException, TimeoutMatchEx {
        Pedido eu;
        try {
            eu = new Pedido(Json.texto(jsonIn, "ip"), Json.inteiro(jsonIn, "porta"),
                            Json.texto(jsonIn, "protocolo").toLowerCase());
        } catch (Exception e) {
            throw new RemoteException("json-in invalido: " + jsonIn);
        }
        log("pedido de " + eu + "  (fila: " + fila.size() + ")");

        // se alguem ja esta esperando com o mesmo protocolo, forma o par
        for (Iterator<Pedido> it = fila.iterator(); it.hasNext(); ) {
            Pedido outro = it.next();
            if (outro.protocolo.equals(eu.protocolo)
                    && !(outro.ip.equals(eu.ip) && outro.porta == eu.porta)) {
                it.remove();
                outro.resposta = Json.resposta(eu.ip, eu.porta, true); // quem esperava inicia
                notifyAll(); // acorda a thread dele
                log("MATCH: " + outro + " (inicia)  x  " + eu);
                return Json.resposta(outro.ip, outro.porta, false);
            }
        }

        // se ninguem compativel, entra na fila e espera com tempo limite
        fila.add(eu);
        long limite = System.currentTimeMillis() + timeoutMs;
        try {
            while (eu.resposta == null) {
                long falta = limite - System.currentTimeMillis();
                if (falta <= 0) break;
                wait(falta); // libera o lock enquanto espera
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (eu.resposta == null) {
            fila.remove(eu);
            log("timeout de " + eu + ", removido da fila");
            throw new TimeoutMatchEx(timeoutMs / 1000);
        }
        return eu.resposta;
    }

    private static void log(String s) {
        System.out.println("[match " + java.time.LocalTime.now().withNano(0) + "] " + s);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java MatchImpl <portaRegistry> [timeoutSegundos]");
            return;
        }
        int porta = Integer.parseInt(args[0]);
        long timeout = (args.length > 1 ? Long.parseLong(args[1]) : 60) * 1000;
        try {
            MatchImpl srv = new MatchImpl(timeout);
            MatchIntf stub = (MatchIntf) UnicastRemoteObject.exportObject(srv, 0);
            Registry registry = LocateRegistry.createRegistry(porta);
            registry.rebind("MatchSrv", stub);
            log("Servidor de Match ativo. Registry na porta " + porta + ", timeout " + timeout / 1000 + "s");
        } catch (Exception e) {
            System.err.println("Erro no servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }
}