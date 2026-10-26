package com.hr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Décodage des en-têtes d'email renvoyés tels quels par l'API Gmail : mots encodés RFC 2047
 * ({@code =?UTF-8?B?...?=}, {@code =?ISO-8859-1?Q?...?=}) et champ « From » ({@code "Nom" <adresse>}).
 */
public final class MimeHeaders {

    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");

    /** Expéditeur : nom affiché (peut être vide) et adresse. */
    public record Mailbox(String name, String email) {
    }

    private MimeHeaders() {
    }

    /** Décode les mots encodés ; un mot illisible est laissé tel quel. Jamais null. */
    public static String decodeWords(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        Matcher m = ENCODED_WORD.matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean previousWasEncoded = false;
        while (m.find()) {
            String between = value.substring(last, m.start());
            // RFC 2047 : les blancs entre deux mots encodés qui se suivent ne sont pas affichés
            if (!(previousWasEncoded && between.isBlank())) {
                out.append(between);
            }
            out.append(decodeWord(m.group(1), m.group(2), m.group(3), m.group()));
            last = m.end();
            previousWasEncoded = true;
        }
        out.append(value.substring(last));
        return out.toString();
    }

    /** Analyse un champ « From » : {@code "Jérôme Dupont" <j@x.fr>}, {@code Jérôme <j@x.fr>} ou {@code j@x.fr}. */
    public static Mailbox parseMailbox(String header) {
        if (header == null || header.isBlank()) {
            return new Mailbox("", "");
        }
        String value = header.strip();
        int lt = value.lastIndexOf('<');
        int gt = lt < 0 ? -1 : value.indexOf('>', lt + 1);
        if (lt >= 0 && gt > lt) {
            String email = value.substring(lt + 1, gt).strip();
            String name = decodeWords(unquote(value.substring(0, lt).strip())).strip();
            return new Mailbox(name, email);
        }
        if (value.indexOf('@') >= 0) {
            return new Mailbox("", value);
        }
        return new Mailbox(decodeWords(unquote(value)).strip(), "");
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    private static String decodeWord(String charsetName, String encoding, String text, String original) {
        try {
            int star = charsetName.indexOf('*'); // « utf-8*fr » : indication de langue (RFC 2231)
            Charset charset = Charset.forName(star >= 0 ? charsetName.substring(0, star) : charsetName);
            byte[] bytes = encoding.equalsIgnoreCase("B") ? Base64.getMimeDecoder().decode(text) : decodeQ(text);
            return new String(bytes, charset);
        } catch (RuntimeException e) {
            // charset inconnu, base64 ou hexadécimal invalide : on garde le texte d'origine
            return original;
        }
    }

    private static byte[] decodeQ(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=' && i + 2 < text.length()) {
                out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }
}
