local prj_dir = os.curdir()
local mill_project = "axi2chi"
target("init")
    set_kind("phony")
    on_run(function (target)
        local execute = os.exec
        execute("git submodule update --init")
        os.cd(prj_dir .. "/rocket-chip")
        execute("git submodule update --init")
        os.cd(prj_dir .. "/rocket-chip/dependencies/hardfloat")
        execute("git submodule update --init")
        print("Submodules have been updated")
    end)

target("Axi2Chi" .. "Top")
    set_kind("phony")
    on_build(function(target)
        local execute = os.exec
        local deleteLineNum = 4
        os.tryrm("build/Axi2ChiTop/*")
        execute("mill -i --jobs 16 %s.runMain testAxi2Chi.Axi2ChiTop -td build", mill_project)
    end)
