package com.colmeia.radiomapper.ui;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextFormatter;
import javafx.util.StringConverter;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Spinners decimais que não brigam com o teclado do usuário.
 *
 * Dois problemas resolvidos aqui:
 *
 * <ol>
 *   <li><b>Vírgula e ponto.</b> O conversor padrão do JavaFX segue o locale:
 *       em pt-BR aceita "5,5" e recusa "5.5". Quem tem teclado numérico digita
 *       ponto, quem digita no alfanumérico usa vírgula, e a planilha de onde o
 *       valor foi copiado pode usar qualquer um dos dois. Aqui os dois valem.</li>
 *   <li><b>Texto inválido.</b> Sem filtro, dá para digitar letras no campo; o
 *       spinner então engole a exceção e volta silenciosamente ao valor antigo,
 *       fazendo parecer que o programa ignorou a edição. O filtro impede a
 *       digitação do que não for número.</li>
 * </ol>
 */
public final class Spinners {

    private Spinners() {}

    /** Aceita opcional sinal, dígitos e no máximo um separador decimal. */
    private static final String PARTIAL = "-?\\d*([.,]\\d*)?";

    public static Spinner<Double> decimal(double min, double max, double value, double step) {
        return decimal(min, max, value, step, 1);
    }

    /**
     * @param decimals casas decimais mostradas ao formatar de volta
     */
    public static Spinner<Double> decimal(double min, double max, double value,
                                          double step, int decimals) {
        double clamped = Math.max(min, Math.min(max, value));
        SpinnerValueFactory.DoubleSpinnerValueFactory factory =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, clamped, step);

        factory.setConverter(new StringConverter<>() {
            @Override public String toString(Double v) {
                if (v == null) return "";
                // Sem zeros à toa: 5 aparece como "5", 5,5 como "5,5".
                String s = String.format(Locale.US, "%." + decimals + "f", v);
                if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
                return s;
            }
            @Override public Double fromString(String s) {
                if (s == null) return factory.getValue();
                String t = s.trim().replace(',', '.');
                if (t.isEmpty() || t.equals("-")) return factory.getValue();
                try {
                    return Double.valueOf(t);
                } catch (NumberFormatException ex) {
                    // Texto impossível: mantém o valor atual em vez de zerar.
                    return factory.getValue();
                }
            }
        });

        Spinner<Double> sp = new Spinner<>(factory);
        sp.setEditable(true);
        sp.setPrefWidth(110);

        UnaryOperator<TextFormatter.Change> filter = c ->
                c.getControlNewText().matches(PARTIAL) ? c : null;
        sp.getEditor().setTextFormatter(new TextFormatter<>(filter));

        // Sem isto, sair do campo sem apertar Enter descartaria o que foi digitado.
        sp.focusedProperty().addListener((o, a, focado) -> {
            if (!focado) commit(sp);
        });
        return sp;
    }

    /**
     * Troca a faixa aceita por um spinner ja criado.
     *
     * A fabrica do JavaFX puxa o valor atual para dentro da nova faixa: quem
     * estreita o limite precisa querer esse efeito, porque o numero digitado
     * muda sozinho. Usado onde o significado do campo muda (altura de mastro
     * nao pode ser negativa; cota absoluta pode).
     */
    public static void setRange(Spinner<Double> sp, double min, double max) {
        if (sp.getValueFactory() instanceof SpinnerValueFactory.DoubleSpinnerValueFactory f) {
            // Ordem importa: alargar antes de estreitar evita que um valor
            // valido na faixa nova seja cortado por um limite ainda antigo.
            if (min < f.getMin()) { f.setMin(min); f.setMax(max); }
            else { f.setMax(max); f.setMin(min); }
        }
    }

    /**
     * Lê o valor SEM mexer no texto do campo.
     *
     * Usado durante a edição ao vivo: {@link #commit} reescreve o editor, e
     * fazer isso a cada tecla atrapalharia quem está digitando.
     */
    public static double value(Spinner<Double> sp, double fallback) {
        Double v = sp.getValue();
        return v == null ? fallback : v;
    }

    /**
     * Aplica o texto digitado e devolve o valor. Spinner editável não comita
     * sozinho: sem isto, digitar 5,5 e clicar direto em OK gravaria o valor
     * anterior.
     */
    public static double commit(Spinner<Double> sp, double fallback) {
        commit(sp);
        Double v = sp.getValue();
        return v == null ? fallback : v;
    }

    private static void commit(Spinner<Double> sp) {
        var factory = sp.getValueFactory();
        if (factory == null) return;
        String text = sp.getEditor().getText();
        Double parsed = factory.getConverter().fromString(text);
        if (parsed != null) factory.setValue(parsed);
        // Reescreve normalizado, para o campo não ficar mostrando "5," ou "05".
        sp.getEditor().setText(factory.getConverter().toString(factory.getValue()));
    }
}
