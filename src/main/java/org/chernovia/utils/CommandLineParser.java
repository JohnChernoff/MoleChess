package org.chernovia.utils;

import java.util.*;

//Credit goes to Oshan Upreti (https://oshanoshu.github.io/) for the following class
public class CommandLineParser {
    List<String> args = new ArrayList<>();
    HashMap<String, List<String>> map = new HashMap<>();
    Set<String> flags = new HashSet<>();

    public CommandLineParser(String arguments[]) {
        this.args = Arrays.asList(arguments);
        map();
    }

    // Return argument names
    public Set<String> getArgumentNames() {
        Set<String> argumentNames = new HashSet<>();
        argumentNames.addAll(flags);
        argumentNames.addAll(map.keySet());
        return argumentNames;
    }

    // Check if flag is given
    public boolean getFlag(String flagName) {
        if (flags.contains(flagName))
            return true;
        return false;
    }

    // Return argument value for particular argument name
    public String[] getArgumentValue(String argumentName) {
        if (map.containsKey(argumentName))
            return map.get(argumentName).toArray(new String[0]);
        else
            return null;
    }

    // Map the flags and argument names with the values 
    public void map() {
        for (String arg : args) {
            if (arg.startsWith("-")) {
                if (args.indexOf(arg) == (args.size() - 1)) {
                    flags.add(arg.replace("-", ""));
                } else if (args.get(args.indexOf(arg) + 1).startsWith("-")) {
                    flags.add(arg.replace("-", ""));
                } else {
                    //List of values (can be multiple)
                    List<String> argumentValues = new ArrayList<>();
                    int i = 1;
                    while (args.indexOf(arg) + i != args.size() && !args.get(args.indexOf(arg) + i).startsWith("-")) {
                        argumentValues.add(args.get(args.indexOf(arg) + i));
                        i++;
                    }
                    map.put(arg.replace("-", ""), argumentValues);
                }
            }
        }
    }
}
