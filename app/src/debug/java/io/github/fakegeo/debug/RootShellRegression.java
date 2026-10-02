package io.github.fakegeo.debug;

import io.github.fakegeo.root.RootCommands;

/** Executes the production comparator in the phone's actual Root shell, rather than a host shell. */
final class RootShellRegression {
    private RootShellRegression() {}
    static String command() {
        return "set -eu\nexport LC_ALL=C\n" + RootCommands.revisionOrderFunction()
                + "fg_check() {\nif fg_revision_le \"$1\" \"$2\"; then fg_actual=0; else fg_actual=$?; fi\n"
                + "[ \"$fg_actual\" = \"$3\" ] || { echo \"FAILED $1 $2 expected=$3 actual=$fg_actual\"; exit 1; }\n"
                + "echo \"PASS $1 $2 result=$fg_actual\"\n}\n"
                + "fg_check 0 1790767099701 0\n"
                + "fg_check 1790767099701 1790767099701 0\n"
                + "fg_check 1790767099700 1790767099701 0\n"
                + "fg_check 1790767099702 1790767099701 1\n"
                + "fg_check 2147483647 2147483648 0\n"
                + "fg_check 9 10 0\nfg_check 10 9 1\n"
                + "fg_check 9223372036854775806 9223372036854775807 0\n"
                + "fg_check 9223372036854775807 9223372036854775807 0\n"
                + "fg_check 0000 0 0\nfg_check 000159 159 0\nfg_check 1x 2 2\n"
                + "echo ROOT_REVISION_REGRESSION_PASSED_12\n";
    }
}
