/// Determines which types of nodes the plugin supports
enum NodeType {
  localGLTF2, // Node with renderable with fileending .gltf in the Flutter asset folder
  webGLB, // Node with renderable with fileending .glb loaded from the internet during runtime
  fileSystemAppFolderGLB, // Node with renderable with fileending .glb in the documents folder of the current app
  fileSystemAppFolderGLTF2, // Node with renderable with fileending .gltf in the documents folder of the current app
  cube, // Node with a programmatically generated cube (no gltf needed). Requires 'cubeWidth/Height/Length' and 'cubeColor' in data map.
  rectangleFrame, // Node with 4 cube edges forming a rectangle frame. Requires 'frameSize', 'frameWidth', 'frameHeight', 'frameColor' in data map.
}
