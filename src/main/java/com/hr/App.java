package com.hr;

import com.hr.gui.MainFrame;

import java.awt.GraphicsEnvironment;

/**
 * Point d'entrée de l'interface graphique Swing.
 */
public class App {

    public static void main(String[] args) {
        if (args.length > 0) {
            System.err.println("Les arguments ne sont pas pris en charge. Lancez l'application sans argument.");
            System.exit(2);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Impossible de lancer l'interface graphique dans un environnement sans affichage.");
            System.exit(1);
        }
        MainFrame.launch();
    }
}
