package io.github.seraphina.obf.common.config;

public final class CommonConfig {

    public static final String[] CLASS_NAME_WORDS = {
            "Almanac", "Anvil", "Arbor", "Arsenal", "Ascent", "Aster", "Avalanche", "Basalt", "Bastion", "Beacon",
            "Belfry", "Beryl", "Boulder", "Bramble", "Brazier", "Bulwark", "Cairn", "Cascade", "Cavern", "Chisel",
            "Cinder", "Citadel", "Clarion", "Cobalt", "Compass", "Crucible", "Cypress", "Dagger", "Delta", "Dominion",
            "Driftwood", "Dune", "Dynamo", "Easel", "Eclipse", "Elixir", "Ember", "Fathom", "Flint", "Fortress",
            "Foundry", "Gantry", "Garland", "Garrison", "Gazette", "Geyser", "Girder", "Glacier", "Granary", "Granite",
            "Grotto", "Halcyon", "Harbor", "Harrow", "Hearth", "Helix", "Herald", "Hollow", "Hurdle", "Hydrant",
            "Inlet", "Iris", "Isthmus", "Ivory", "Javelin", "Juncture", "Juniper", "Kelp", "Keystone", "Kiln",
            "Kestrel", "Lagoon", "Lantern", "Lattice", "Lodestone", "Loom", "Mantle", "Marrow", "Meadow", "Meridian",
            "Monolith", "Nectar", "Nexus", "Nimbus", "Obelisk", "Onyx", "Orchard", "Orbit", "Oasis", "Parapet",
            "Pavilion", "Pinnacle", "Plateau", "Prism", "Quartz", "Quarry", "Quill", "Quiver", "Rampart", "Reef",
            "Ridge", "Ripple", "Sable", "Sanctuary", "Scepter", "Sentinel", "Solstice", "Spire", "Steppe", "Stronghold",
            "Talon", "Tesseract", "Thicket", "Thistle", "Timber", "Trellis", "Tundra", "Umbra", "Vale", "Vanguard",
            "Vantage", "Vellum", "Vertex", "Vessel", "Warden", "Wharf", "Whetstone", "Wildwood", "Willow", "Zenith",
            "Zephyr"
    };

    public static final String[] METHOD_NAME_WORDS = {
            "acquire", "allocate", "amplify", "assemble", "attach", "balance", "broadcast", "calibrate", "capture", "channel",
            "cluster", "collapse", "compile", "compose", "compress", "compute", "conceal", "condense", "configure", "connect",
            "converge", "convert", "coordinate", "correlate", "decode", "decompose", "decrypt", "deduce", "delegate", "deliver",
            "deploy", "describe", "detect", "determine", "digest", "dispatch", "distribute", "divide", "drain", "elevate",
            "embed", "emit", "encode", "enqueue", "enumerate", "evaluate", "evolve", "expand", "extract", "fabricate",
            "factor", "filter", "flatten", "fold", "forecast", "format", "forward", "fragment", "gather", "generate",
            "govern", "harvest", "identify", "ignore", "index", "infer", "inject", "inspect", "install", "integrate",
            "intersect", "invert", "isolate", "iterate", "join", "launch", "layer", "lift", "locate", "maintain",
            "measure", "merge", "migrate", "mirror", "modulate", "mount", "multiplex", "navigate", "normalize", "observe",
            "offset", "optimize", "organize", "outline", "overhaul", "parallelize", "parse", "partition", "patch", "persist",
            "pivot", "poll", "predict", "prepare", "preserve", "probe", "process", "propagate", "publish", "purge",
            "quantify", "query", "queue", "raise", "rank", "rebuild", "reconcile", "recover", "refine", "reflect",
            "refresh", "register", "regulate", "relay", "relocate", "render", "reorder", "repair", "replicate", "resolve",
            "restore", "resume", "retain", "retrieve", "reveal", "reverse", "rotate", "route", "sample", "scan",
            "schedule", "score", "seal", "segment", "select", "serialize", "settle", "shape", "shift", "shuffle",
            "signal", "simplify", "snapshot", "solve", "sort", "spawn", "split", "spread", "stabilize", "stack",
            "stream", "stretch", "submit", "subscribe", "summarize", "supply", "suspend", "sweep", "synchronize", "synthesize",
            "tag", "terminate", "throttle", "toggle", "trace", "track", "transform", "translate", "transmit", "transpose",
            "traverse", "trigger", "trim", "truncate", "unpack", "unwind", "update", "upgrade", "upload", "validate",
            "verify", "weave", "weigh", "wrap", "zoom"
    };

    public static final String[] FIELD_NAME_WORDS = {
            "anchor", "aperture", "archive", "atlas", "badge", "ballot", "banner", "barrel", "basin", "battery",
            "bracket", "bridge", "bucket", "bundle", "cabinet", "cache", "canvas", "capsule", "catalog", "chamber",
            "cipher", "circuit", "clamp", "clock", "coin", "column", "conduit", "corridor", "cradle", "crater",
            "crest", "crystal", "cursor", "cylinder", "docket", "domain", "dossier", "duct", "eddy", "enamel",
            "engine", "envelope", "facet", "fence", "filament", "flange", "folio", "fossil", "fountain", "frame",
            "frontier", "furnace", "gadget", "gallery", "gauge", "gear", "glyph", "gradient", "gravel", "grid",
            "groove", "gutter", "hammer", "harness", "hatch", "hinge", "hopper", "horizon", "inkwell", "junction",
            "kernel", "kiosk", "ledger", "lens", "lever", "ligament", "liner", "locus", "magnet", "manifold",
            "marker", "matrix", "membrane", "mesh", "metric", "milestone", "mosaic", "motif", "nozzle", "nucleus",
            "palette", "panel", "parcel", "pavement", "pendant", "pigment", "pillar", "pipeline", "piston", "plank",
            "plasma", "pocket", "portal", "powder", "profile", "pulley", "pump", "rack", "radian", "ratio",
            "reactor", "recess", "region", "reservoir", "residue", "ribbon", "ring", "rivet", "rotor", "rudder",
            "scaffold", "scope", "scribe", "sector", "sensor", "sequence", "shaft", "shard", "sheath", "shuttle",
            "sieve", "silo", "socket", "spindle", "spiral", "spur", "stator", "stitch", "strata", "string",
            "swivel", "tab", "tangent", "tapestry", "template", "tensor", "terrace", "thread", "threshold", "token",
            "torque", "tower", "trench", "turbine", "turret", "valve", "vault", "vector", "veneer", "verdict"
    };

    public static final String[] PACKAGE_NAME_WORDS = {
            "alcove", "aurora", "canopy", "chasm", "cobble", "cove", "crevasse", "dell", "estuary", "fen",
            "fjord", "glade", "gorge", "grove", "gulch", "heath", "hedge", "hinterland", "knoll", "marsh",
            "mesa", "moor", "moraine", "outcrop", "pasture", "ravine", "riverbed", "savanna", "shoal", "slope",
            "strand", "summit", "swamp", "tide", "valley", "wetland", "woodland", "abyss", "bayou", "bedrock",
            "bluff", "bracken", "brook", "canyon", "cirque", "cliff", "crag", "creek", "esker", "fern",
            "fissure", "floodplain", "foothill", "forest", "gully", "hamlet", "isle", "jungle", "ledge", "mire",
            "oxbow", "pond", "prairie", "quagmire", "reed", "sandbar", "seaway", "spring", "torrent", "underbrush",
            "vista", "waterway", "wilds", "haven"
    };
}
