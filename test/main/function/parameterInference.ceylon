void parameterInference() {
    @type:"Iterable<Character,Null>" value mapped = "hello world".map((c) => c.uppercased);
    @type:"Iterable<Character,Null>" value filtered = "hello world".filter((c) { return !c.whitespace; });
    @type:"Integer" value folded = (1..10).fold(0)((r, x) => r+x);
    @type:"Integer" value folded2 = (1..10).fold(0)((r, x) { Integer r; Integer x; return r+x; });
    @type:"Integer" value folded3 = (1..10).fold(0)((r, x) { return r+x; });
    @type:"Integer" value reduced = (1..10).reduce<Integer>((r, x) => r+x);
    @type:"Float|Integer" value reduced2 = (1..10).reduce<Float>((r, x) {
        switch (r) 
        case (is Integer) { return (r+x).float; } 
        case (is Float) { return r+x; } 
    });
    function float(Float|Integer num) {
        switch (num) 
        case (is Integer) { return num.float; } 
        case (is Float) { return num; } 
    }
    @type:"Float|Integer" value reduced3 = (1..10).reduce<Float>((r, x) => float(r)+x);
    
    T? fun<T>({T*} ts)(Boolean match(T t)) => ts.find(match);
    Character?(Boolean(Character)) fun1 = fun("goodbye*world");
    
    Character? found
            = fun("hello-world")
    ((ch)=>!ch.letter);
    Character? found1
            = fun1((ch)=>!ch.letter);
}