package io.github.leocb.ls9.examples;

import io.github.leocb.ls9.Ls9Console;
import io.github.leocb.ls9.Ls9Listener;
import io.github.leocb.ls9.Ls9ParameterEvent;
import io.github.leocb.ls9.transport.JavaMidiTransport;

/**
 * Small end-to-end example.
 *
 * <pre>
 *   java -cp out io.github.leocb.ls9.examples.Example 192.168.0.128
 *   java -cp out io.github.leocb.ls9.examples.Example 192.168.0.128 00:1A:2B:3C:4D:5E
 *   java -cp out io.github.leocb.ls9.examples.Example --midi "UNIT1 Port1"
 * </pre>
 */
public final class Example {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: Example <host> [mac] | --midi <port-name> | --list-midi");
            return;
        }
        if (args[0].equals("--list-midi")) {
            JavaMidiTransport.listPorts().forEach(System.out::println);
            return;
        }

        try (Ls9Console ls9 = args[0].equals("--midi")
                ? Ls9Console.builder().transport(new JavaMidiTransport(args[1])).open()
                : (args.length > 1
                        ? Ls9Console.connect(args[0], args[1])
                        : Ls9Console.connect(args[0]))) {

            ls9.addListener(new Ls9Listener() {
                @Override
                public void onParameterChange(Ls9ParameterEvent event) {
                    if (event.address().element() == 0x0033
                            && event.address().channel() == 0) {
                        System.out.println("INPUT 1 fader moved: raw=" + event.value());
                    }
                }
            });

            System.out.println("INPUT 1 fader before: " + ls9.input(1).faderDb() + " dB");
            ls9.input(1).setFaderDb(-6.0);
            System.out.println("INPUT 1 fader after:  " + ls9.input(1).faderDb() + " dB");
            ls9.input(1).setOn(true);
        }
    }
}
