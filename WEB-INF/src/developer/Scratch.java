package developer;


import oculusPrime.Util;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Scratch {

    public void regexp() {
        Pattern quality = Pattern.compile("^\\s*asdf asdf");
        Matcher mat;
        mat = quality.matcher("fdsaf"); //    /^\s*Quality=/
        if (mat.find()) {
            mat.find();
        }

        System.out.println("192.168.0.107".substring(0,4));

        if ("Gableem".matches(".*ei.*"))
            System.out.println("match");

        System.out.println("ok here we go");
        System.out.println("192.168.0.107".replaceFirst("\\.\\d+\\.\\d+$", ""));
    }

    public static void main(String[] args) {
//        new Scratch().regexp();

        double mAhCapacity= 4520.0;
        double newmAhCapacity = 7000;
        if (Math.abs(newmAhCapacity-mAhCapacity)/mAhCapacity > 0.2) {
            if (newmAhCapacity > mAhCapacity ) newmAhCapacity = mAhCapacity*1.2;
            else newmAhCapacity = mAhCapacity*0.8;
        }

        System.out.println(newmAhCapacity);

    }
}

