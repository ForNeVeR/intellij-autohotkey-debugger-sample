#Requires AutoHotkey v2.0

Loop 10
{
    ProcessIteration(A_Index)
}

MsgBox("Done!")

ProcessIteration(index) {
    currentMessage := "Iteration number: " . index

    debugInfo := {
        index: index,
        text: currentMessage,
        isEven: (Mod(index, 2) == 0)
    }

    MsgBox(debugInfo.text)
}