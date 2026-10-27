// TimeoutMatchEx: lancada quando nenhum oponente aparece dentro do tempo limite
public class TimeoutMatchEx extends Exception {

    public TimeoutMatchEx(long segundos) {
        super("Nenhum oponente encontrado em " + segundos + "s. Tente novamente.");
    }
}