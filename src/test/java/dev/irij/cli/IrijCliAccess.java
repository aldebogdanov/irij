package dev.irij.cli;

/** Test access to IrijCli's package-private argument split. */
public final class IrijCliAccess {
    private IrijCliAccess() {}

    public static String[] programArgsAfter(String[] args, int fileIndex) {
        return IrijCli.programArgsAfter(args, fileIndex);
    }
}
