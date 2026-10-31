extends SceneTree
## Transparent robot still for the native start screen. Run against the game-platform Hovel room.
func _initialize():
	call_deferred("capture")
func capture():
	var host = load("res://environment/tests/test_room_host.gd").new()
	Engine.register_singleton("HovelHost", host)
	host.sent = JSON.stringify({"companionId":"11111111-1111-4111-8111-111111111111", "name":"Ember", "status":"At home", "preview":""})
	var room = load("res://room.tscn").instantiate()
	root.add_child(room)
	await create_timer(1.0).timeout
	var actor = room.get_node("Environment/Actors/Robot")
	actor.set_physics_process(false)
	actor.set_process(false)
	actor.driver.set_process(false)
	actor.driver.reset(Vector3.ZERO)
	actor.viewport.size = Vector2i(512, 512)
	actor.camera.size = 1.8
	actor.camera.position = Vector3(0.0, 1.2, 5.0)
	actor.camera.look_at(Vector3(0.0, 0.8, 0.0))
	for i in 12: await process_frame
	await RenderingServer.frame_post_draw
	var image = actor.viewport.get_texture().get_image()
	var output = ""
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--output="): output = arg.substr(9)
	assert(output.is_absolute_path(), "Pass -- --output=/absolute/hovel_robot.png")
	assert(image.save_png(output) == OK)
	print("ROBOT_START_STILL_SAVED")
	room.free()
	Engine.unregister_singleton("HovelHost")
	host.free()
	quit()
