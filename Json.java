// Json: montagem e leitura das mensagens JSON do projeto

public final class Json {

    private Json() {}

    // montagem
    public static String pedido(String ip, int porta, String protocolo) {
        return String.format("{\"ip\": \"%s\", \"porta\": %d, \"protocolo\": \"%s\"}", ip, porta, protocolo);
    }

    public static String resposta(String ip, int porta, boolean inicia) {
        return String.format("{\"ip\": \"%s\", \"porta\": %d, \"inicia\": %b}", ip, porta, inicia);
    }

    public static String jogada(int numero, int casa, int[] tab, String resultado) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < tab.length; i++) {
            if (i > 0) t.append(", ");
            t.append(tab[i]);
        }
        return String.format("{\"Numero\": %d, \"Jogada\": %d, \"Tabuleiro\": [%s], \"Resultado\": \"%s\"}",
                             numero, casa, t, resultado);
    }

    // leitura
    public static String texto(String json, String campo) {
        return valor(json, campo).replace("\"", "");
    }

    public static int inteiro(String json, String campo) {
        return Integer.parseInt(valor(json, campo));
    }

    public static boolean booleano(String json, String campo) {
        return Boolean.parseBoolean(valor(json, campo));
    }

    public static int[] vetor(String json, String campo) {
        String v = valor(json, campo);
        String[] partes = v.substring(1, v.length() - 1).split(",");
        int[] r = new int[partes.length];
        for (int i = 0; i < partes.length; i++) r[i] = Integer.parseInt(partes[i].trim());
        return r;
    }

    // devolve o texto do valor de um campo
    private static String valor(String json, String campo) {
        int i = json.indexOf("\"" + campo + "\"");
        if (i < 0) throw new IllegalArgumentException("campo ausente: " + campo);
        int inicio = json.indexOf(':', i) + 1;
        int fim = json.charAt(skip(json, inicio)) == '['
                ? json.indexOf(']', inicio) + 1
                : proximoSeparador(json, inicio);
        return json.substring(inicio, fim).trim();
    }

    private static int skip(String s, int i) {
        while (Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int proximoSeparador(String s, int i) {
        while (s.charAt(i) != ',' && s.charAt(i) != '}') i++;
        return i;
    }
}